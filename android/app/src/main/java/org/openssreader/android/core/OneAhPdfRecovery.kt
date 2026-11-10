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
    private const val MAX_COMPRESSED = 64 * 1024 * 1024
    private const val MAX_INFLATED = 128 * 1024 * 1024
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
        val result = ByteArrayOutputStream(minOf(stream.size * 2L, 1024 * 1024L).toInt())
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
        val indexAndDelta = Pdf1AhParameters.derive(inner)
        val index = indexAndDelta.first
        val delta = indexAndDelta.second
        val limit = original.size - 4 // keep Adler32 untouched
        if (index !in 0 until limit) throw Invalid("1AH 恢复位置越界")
        val subtype = byte(inner,3)
        if (subtype in 0x11..0x18 && delta !in 0 until limit) throw Invalid("1AH 第二位置越界")
        val validOriginal = try { inflatedStrict(original) } catch (_: DataFormatException) { null }
        if (validOriginal != null) {
            if (!pdfFramed(validOriginal)) throw Invalid("解压成功，但不是完整 PDF 文件帧")
            return Segment(validOriginal, subtype, false, original.size, emptyList())
        }
        val changed = original.copyOf()
        val positions = mutableListOf<Int>()
        when (subtype) {
            in 0x19..0x1c -> {
                val mid = (index ushr 1) + (delta ushr 1)
                if (mid >= limit) throw Invalid("1AH 单字节混合来源越界")
                val mixed = if (mid != index) byte(original,index) xor byte(original,mid) else byte(original,index)
                changed[index] = ((mixed - (delta and 255)) and 255).toByte()
                positions.add(index)
            }
            in 0x11..0x14 -> {
                val lower = minOf(index,delta)
                val higher = maxOf(index,delta)
                val stride = if (higher - lower == 17) 19 else 17
                if (higher + stride >= limit) throw Invalid("1AH 双位置混合来源越界")
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
            else -> throw Invalid("未知的 1AH PDF 子类型")
        }
        if (changed.contentEquals(original)) throw Invalid("恢复运算未产生任何变化")
        val pdf = try { inflatedStrict(changed) }
                  catch (_: DataFormatException) { throw Invalid("1AH 恢复后仍未通过 zlib/Adler32 完整校验") }
        if (!pdfFramed(pdf)) throw Invalid("1AH 恢复后没有完整 PDF 文件帧")
        return Segment(pdf, subtype, true, original.size, positions)
    }
}
