package org.openssreader.android.core

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Pure Kotlin, strict PDF-backed HH/0x4602 recovery.
 * Uses the pinned, deterministic PDF subtype parameter derivation from
 * Pdf1AhParameters, NOT a book-specific byte patch or brute-force search.
 *
 * Port: openssreader/vendor/color/src/vendor_pdf_backed_1ah_recovery.py
 * This only recovers a compressed PDF *segment*; it cannot itself merge the
 * multi-page PDFs or replace a structural PDF library's validation.
 */
object OneAhPdfRecovery {
    // Enforced per-device; only a single segment is held in memory at once.
    private val MAX_COMPRESSED get() = PdzCompatibilityLimits.maxPdfCompressedBytes
    private val MAX_INFLATED get() = PdzCompatibilityLimits.maxPdfInflatedBytes
    private const val MAX_FALLBACK_NANOS = 15_000_000_000L
    private const val SEARCH_INDEX_LIMIT = 600
    // These are learned *stream transform* pairs, not device-authorization data.
    private val priorPairs = java.util.ArrayDeque<Pair<Int, Int>>()
    class Invalid(message: String): IllegalArgumentException(message)
    data class Segment(val pdfBytes: ByteArray, val subtype: Int, val repaired: Boolean,
                       val compressedSize: Int, val repairIndexes: List<Int>)

    private fun byte(data: ByteArray, at: Int): Int = data[at].toInt() and 255
    private fun u32(data: ByteArray, at: Int): Long =
        (byte(data, at).toLong() or (byte(data, at+1).toLong() shl 8) or
            (byte(data, at+2).toLong() shl 16) or (byte(data, at+3).toLong() shl 24))
    fun accepts(clear: ByteArray): Boolean = clear.size >= 140 &&
        byte(clear,0) == 'H'.code && byte(clear,1) == 'H'.code &&
        byte(clear,2) == 0x02 && byte(clear,3) == 0x46 &&
        byte(clear,15) in 0x11..0x1c

    private fun inflatedStrict(stream: ByteArray): ByteArray {
        val inflater = Inflater(false)
        val result = ByteArrayOutputStream(minOf(stream.size.toLong() * 2L, 1024 * 1024L).toInt())
        try {
            inflater.setInput(stream)
            val buffer = ByteArray(32768)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n > 0) {
                    if (result.size().toLong() + n > MAX_INFLATED) throw Invalid("1AH PDF 解压超出内存限制")
                    result.write(buffer, 0, n)
                } else if (inflater.needsDictionary() || inflater.needsInput()) {
                    throw DataFormatException("1AH zlib 流不完整")
                } else {
                    throw DataFormatException("1AH zlib 解码无进展")
                }
            }
            if (inflater.remaining != 0) throw Invalid("1AH zlib 流后存在多余字节")
            return result.toByteArray()
        } finally { inflater.end() }
    }

    private fun pdfFramed(pdf: ByteArray): Boolean {
        if (pdf.size < 16 || !pdf.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())) return false
        val from = maxOf(0, pdf.size - 2048)
        val eof = "%%EOF".toByteArray()
        // This is a fast framing gate only: full xref/pages verification is
        // mandatory before accepting an exported or cached PDF.
        return (from..pdf.size - eof.size).any { i -> eof.indices.all { j -> pdf[i+j] == eof[j] } }
    }
    /** Peek at the first PDF bytes, avoiding full (up to 256 MiB) inflations for
     * almost every wrong pair. Full Adler32 + PDF framing is still required. */
    private fun pdfPrefix(stream: ByteArray): Boolean {
        val inflater = Inflater(false)
        try {
            inflater.setInput(stream)
            val header = ByteArray(8)
            val count = inflater.inflate(header)
            return count >= 5 && header.copyOfRange(0, 5).contentEquals("%PDF-".toByteArray())
        } catch (_: DataFormatException) {
            return false
        } finally { inflater.end() }
    }
    private fun c100(stream: ByteArray, index: Int, param: Int): Int? {
        if (index !in 0 until stream.size - 4 || param !in 0..511) return null
        val mid = (index ushr 1) + (param ushr 1)
        if (mid !in 0 until stream.size - 4) return null
        val mixed = if (mid != index) byte(stream,index) xor byte(stream,mid) else byte(stream,index)
        return (mixed - param) and 255
    }
    private fun strictCandidate(candidate: ByteArray): ByteArray? {
        if (!pdfPrefix(candidate)) return null
        return try {
            inflatedStrict(candidate).takeIf(::pdfFramed)
        } catch (_: DataFormatException) { null }
          catch (_: Invalid) { null }
    }
    private fun fallbackSearch(original: ByteArray): Pair<ByteArray, Pair<Int,Int>>? {
        val deadline = System.nanoTime() + MAX_FALLBACK_NANOS
        val candidate = original.copyOf()
        // First try pairs previously successful within this app process.
        val cached = synchronized(priorPairs) { priorPairs.toList() }
        for ((index, param) in cached) {
            if (System.nanoTime() >= deadline) return null
            val value = c100(original,index,param) ?: continue
            if (value == byte(original,index)) continue
            candidate[index] = value.toByte()
            val pdf = strictCandidate(candidate)
            candidate[index] = original[index]
            if (pdf != null) return pdf to (index to param)
        }
        // Bounded search (Linux repair_1ah.py C100 mixer): indices 2..599;
        // c100 parameters 0..511. Never bypass Adler or accept truncated PDFs.
        val upper = minOf(SEARCH_INDEX_LIMIT, original.size - 4)
        for (index in 2 until upper) {
            if (System.nanoTime() >= deadline) return null
            val tried = BooleanArray(256)
            for (param in 0..511) {
                if ((param and 15) == 0 && System.nanoTime() >= deadline) return null
                val value = c100(original,index,param) ?: continue
                if (value == byte(original,index) || tried[value]) continue
                tried[value] = true
                candidate[index] = value.toByte()
                val pdf = strictCandidate(candidate)
                candidate[index] = original[index]
                if (pdf != null) return pdf to (index to param)
            }
            // Raw single-byte recovery: like Linux _scan_raw, but only exact
            // zlib/Adler validated results can leave this function.
            for (value in 0..255) {
                if ((value and 15) == 0 && System.nanoTime() >= deadline) return null
                if (value == byte(original,index) || tried[value]) continue
                candidate[index] = value.toByte()
                val pdf = strictCandidate(candidate)
                candidate[index] = original[index]
                if (pdf != null) return pdf to (index to 0)
            }
        }
        return null
    }
    private fun learn(index: Int, param: Int) {
        if (param == 0) return // raw-byte repair has no reusable C100 parameter
        synchronized(priorPairs) {
            priorPairs.remove(index to param)
            priorPairs.addFirst(index to param)
            while (priorPairs.size > 64) priorPairs.removeLast()
        }
    }
    fun recover(clear: ByteArray): Segment {
        if (!accepts(clear)) throw Invalid("不支持的 HH PDF 内层编码")
        val inner = clear.copyOfRange(12, 140)
        if (byte(inner,0) != 0x80 || byte(inner,1) != 0 || byte(inner,2) != 0x46 ||
            byte(inner,3) != byte(clear,15)) throw Invalid("1AH 内层页头与外层子类型不一致")
        val off = u32(clear, 24)
        val len = u32(inner, 16)
        if (off < 140 || off > clear.size || len < 10 || len > MAX_COMPRESSED ||
            off + len != clear.size.toLong()) throw Invalid("1AH 压缩流边界不合法")
        val original = clear.copyOfRange(off.toInt(), clear.size)
        val subtype = byte(inner,3)
        val validOriginal = try { inflatedStrict(original) } catch (_: DataFormatException) { null }
        if (validOriginal != null) {
            if (!pdfFramed(validOriginal)) throw Invalid("解压成功，但不是完整 PDF 文件帧")
            return Segment(validOriginal, subtype, false, original.size, emptyList())
        }
        // A header-derived exact recovery is preferred; the bounded search
        // is used only when this exact attempt fails integrity validation.
        val indexAndDelta = try { Pdf1AhParameters.derive(inner) }
            catch (exc: IllegalArgumentException) { null }
        val index = indexAndDelta?.first ?: -1
        val delta = indexAndDelta?.second ?: -1
        val limit = original.size - 4 // keep Adler32 untouched
        val changed = original.copyOf()
        val positions = mutableListOf<Int>()
        if (index in 0 until limit && (subtype !in 0x11..0x18 || delta in 0 until limit)) when (subtype) {
            in 0x19..0x1c -> {
                val mid = (index ushr 1) + (delta ushr 1)
                if (mid >= limit) return recoverWithFallback(original, subtype)
                val mixed = if (mid != index) byte(original,index) xor byte(original,mid) else byte(original,index)
                changed[index] = ((mixed - (delta and 255)) and 255).toByte()
                positions.add(index)
            }
            in 0x11..0x14 -> {
                val lower = minOf(index,delta)
                val higher = maxOf(index,delta)
                val stride = if (higher - lower == 17) 19 else 17
                if (higher + stride >= limit) return recoverWithFallback(original, subtype)
                changed[higher] = (byte(changed,higher) xor byte(changed,higher+stride)).toByte()
                changed[lower] = (byte(changed,lower) xor byte(changed,lower+stride)).toByte()
                positions.add(higher); positions.add(lower)
            }
            in 0x15..0x18 -> {
                val lower = minOf(index,delta)
                val higher = maxOf(index,delta)
                val mask = byte(changed,lower) xor byte(changed,higher)
                val old = changed.copyOfRange(lower+1,higher)
                for (i in old.indices) changed[lower+1+i] = (byte(old,old.size-1-i) xor mask).toByte()
                positions.add(lower+1); positions.add(higher)
            }
            else -> return recoverWithFallback(original, subtype)
        }
        if (!changed.contentEquals(original)) {
            val pdf = try { inflatedStrict(changed) }
                      catch (_: DataFormatException) { null }
                      catch (_: Invalid) { null }
            if (pdf != null && pdfFramed(pdf))
                return Segment(pdf, subtype, true, original.size, positions)
        }
        return recoverWithFallback(original, subtype)
    }
    private fun recoverWithFallback(original: ByteArray, subtype: Int): Segment {
        // The Linux C100 fallback is established for format 0x1a. For other
        // proprietary subtypes retain the trusted header-derived path.
        if (subtype != 0x1a) throw Invalid("1AH 精确恢复未通过完整 zlib/Adler32 校验")
        val recovered = fallbackSearch(original)
            ?: throw Invalid("1AH 有界备用恢复失败或已达到时间上限；不输出未验证 PDF")
        learn(recovered.second.first, recovered.second.second)
        return Segment(recovered.first, subtype, true, original.size, listOf(recovered.second.first))
    }
}
