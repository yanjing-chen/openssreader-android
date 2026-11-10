package org.openssreader.android.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Real contents.dat chapter tree: SSCNTS + zlib(GB18030 rows). */
data class Chapter(val code: String, val title: String, val level: Int, val bodyPage: Int,
    val documentPageZeroBased: Int?)

data class PageLayout(val physicalPages: List<PdzEntry>?, val front: Int, val contents: Int, val body: Int,
    val verifiedTotalPages: Int?, val mappingAvailable: Boolean)
object PdzContentsReader {
    private val front = Regex("fow(\\d{3})\\.pdg", RegexOption.IGNORE_CASE)
    private val contents = Regex("!(\\d{5})\\.pdg", RegexOption.IGNORE_CASE)
    private val body = Regex("(\\d{6})\\.pdg", RegexOption.IGNORE_CASE)
    private val segments = Regex("(\\d+)_(\\d+)\\.pdg", RegexOption.IGNORE_CASE)
    private val codePattern = Regex("(?:[0-9a-z]{2})+")
    private fun numbered(records: List<PdzEntry>, pattern: Regex): List<PdzEntry>? {
        val matches = records.mapNotNull { r -> pattern.matchEntire(r.name)?.let { Pair(it.groupValues[1].toInt(), r) } }
            .sortedBy { it.first }
        return matches.map { it.first }.takeIf { numbers -> numbers == (1..numbers.size).toList() }
            ?.let { matches.map { r -> r.second } }
    }
    fun layout(catalog: PdzCatalog): PageLayout {
        val entries = catalog.entries
        val a = numbered(entries, front)
        val b = numbered(entries, contents)
        val c = numbered(entries, body)
        val segs = entries.filter { segments.matches(it.name) }
        val appendix = entries.any { Regex("att\\d{3}\\.pdg", RegexOption.IGNORE_CASE).matches(it.name) }
        val known = listOf(front, contents, body, segments, Regex("att\\d{3}\\.pdg", RegexOption.IGNORE_CASE))
        val unknown = entries.any { it.name.endsWith(".pdg", true) && known.none { p -> p.matches(it.name) } }
        // A1 supports a complete physical-page ordering only for individual page records.
        val segmentPairs = segs.mapNotNull { segments.matchEntire(it.name)?.let { m ->
            Pair(m.groupValues[1].toIntOrNull(), m.groupValues[2].toIntOrNull())
        } }.sortedBy { it.first }
        var segPageCount: Int? = null
        if (segs.isNotEmpty() && segmentPairs.size == segs.size && c?.isEmpty() == true) {
            var next = 1L
            for ((start, count) in segmentPairs) {
                if (start == null || count == null || start.toLong() != next || count < 1 || next + count > 1_000_000L) {
                    next = -1L; break
                }
                next += count
            }
            if (next > 1L) segPageCount = (next - 1).toInt()
        }
        // Mixed PDF bundles and ordinary page records share one BODY index.
        // Use a separate strict plan instead of assuming one-pdg-per-page.
        val mixedPlan = if (segs.isNotEmpty()) PdzMixedPagePlan.plan(catalog) else null
        val bodyCount = mixedPlan?.bodyPages ?: if (segs.isNotEmpty()) segPageCount else c?.size
        val validMapping = (mixedPlan != null) ||
            (a != null && b != null && bodyCount != null && bodyCount > 0 && !appendix && !unknown)
        val mapped = if (validMapping && segs.isEmpty()) a!! + b!! + c!! else null
        val total = if (validMapping) a!!.size + b!!.size + bodyCount!! else null
        return PageLayout(mapped, a?.size ?: 0, b?.size ?: 0, bodyCount ?: 0, total, validMapping)
    }
    fun chapters(file: File, catalog: PdzCatalog, metadata: BookMetadata?, layout: PageLayout): List<Chapter>? {
        val entry = catalog.entries.firstOrNull { it.name.equals("contents.dat", true) } ?: return null
        val bytes = PdzCatalogReader.resource(file, entry, 8 * 1024 * 1024)
        if (bytes.size < 40 || !bytes.copyOfRange(0, 6).contentEquals("SSCNTS".toByteArray()) ||
            bytes[6] != 1.toByte() || bytes[7] != 0.toByte())
            throw PdzFormatException("Unsupported SSCNTS header")
        val ssid = metadata?.ssid?.toLongOrNull()
        val encodedSsid = (ByteBuffer.wrap(bytes, 12, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL)
        if (ssid != null && encodedSsid != 0L && ssid != encodedSsid) throw PdzFormatException("TOC SSID mismatch")
        val inflater = Inflater()
        val data = bytes.copyOfRange(40, bytes.size)
        val output = java.io.ByteArrayOutputStream()
        try {
            inflater.setInput(data)
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count > 0) {
                    if (output.size() + count > 16 * 1024 * 1024) throw PdzFormatException("TOC inflated size too large")
                    output.write(buf, 0, count)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    throw PdzFormatException("Incomplete TOC zlib stream")
                } else {
                    throw PdzFormatException("Invalid TOC zlib stream")
                }
            }
            if (inflater.remaining != 0) throw PdzFormatException("Extra data after TOC zlib stream")
        } catch (exc: DataFormatException) {
            throw PdzFormatException("Damaged TOC zlib data")
        } finally { inflater.end() }
        val rows = output.toByteArray().splitOn(10.toByte())
        val result = ArrayList<Chapter>()
        val seen = HashSet<String>()
        for (line in rows) {
            val trimmed = line.dropLastWhile { it == 13.toByte() }.toByteArray()
            if (trimmed.isEmpty()) continue
            if (result.size >= 100_000) throw PdzFormatException("Too many TOC rows")
            val fields = trimmed.splitOn('|'.code.toByte())
            if (fields.size != 6 || fields.last().isNotEmpty()) throw PdzFormatException("Invalid TOC row fields")
            val code = ascii(fields[1]); val pageText = ascii(fields[2]); val aux1 = ascii(fields[3]); val aux2 = ascii(fields[4])
            if (!codePattern.matches(code) || !seen.add(code)) throw PdzFormatException("Invalid/duplicate chapter code")
            if (pageText.isEmpty() || aux1.isEmpty() || aux2.isEmpty() ||
                !pageText.all(Char::isDigit) || !aux1.all(Char::isDigit) || !aux2.all(Char::isDigit))
                throw PdzFormatException("Invalid chapter page fields")
            val page = pageText.toIntOrNull()?.takeIf { it >= 1 } ?: throw PdzFormatException("Invalid chapter number")
            if (code.length > 2 && !seen.contains(code.dropLast(2))) throw PdzFormatException("Chapter parent missing")
            // Java's default decoder substitutes U+FFFD for malformed GB18030 title bytes.
            val title = String(fields[0], Charset.forName("GB18030")).trim()
            if (title.isEmpty()) throw PdzFormatException("Blank chapter title")
            val physical = if (layout.mappingAvailable && page <= layout.body)
                layout.front + layout.contents + page - 1 else null
            result.add(Chapter(code, title, code.length / 2, page, physical))
        }
        return result
    }
    private fun ascii(data: ByteArray): String {
        if (data.any { (it.toInt() and 0xff) > 127 }) throw PdzFormatException("TOC structural field is not ASCII")
        return String(data, Charsets.US_ASCII)
    }
    private fun ByteArray.splitOn(delimiter: Byte): List<ByteArray> {
        val result = ArrayList<ByteArray>(); var start = 0
        for (i in indices) if (this[i] == delimiter) {
            result.add(copyOfRange(start, i)); start = i + 1
        }
        result.add(copyOfRange(start, size))
        return result
    }
}
