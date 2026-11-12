package org.openssreader.android.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Exact page-order and stream recovery gate for PDF-backed multi-page
 * PDZ segments named `first_count.pdg`. Fail closed, never skip a segment.
 * Each recovered PDF must pass an independent structural parser and return the
 * actual number of its pages through `verifyPdfPages`, not just `%PDF/%%EOF`.
 * Does not write an exported document or claim that PDF merging is complete.
 */
object PdzSegmentReader {
    private val bundleName = Regex("([0-9]+)_([0-9]+)\\.pdg", RegexOption.IGNORE_CASE)
    private const val MAX_PAGES = PdzCompatibilityLimits.MAX_BOOK_PAGES

    data class Bundle(val entry: PdzEntry, val firstPage: Int, val expectedCount: Int)
    data class Plan(val bundles: List<Bundle>, val totalPages: Int)

    fun plan(catalog: PdzCatalog): Plan? {
        val pageRecords = catalog.entries.filter { it.name.endsWith(".pdg", true) }
        if (pageRecords.isEmpty()) return null
        val matched = pageRecords.map { entry ->
            val match = bundleName.matchEntire(entry.name) ?: return null
            val first = match.groupValues[1].toIntOrNull() ?: throw PdzFormatException("分段起始页超出范围")
            val count = match.groupValues[2].toIntOrNull() ?: throw PdzFormatException("分段页数超出范围")
            Bundle(entry, first, count)
        }
        val ordered = matched.sortedBy { it.firstPage }
        var next = 1L
        for (segment in ordered) {
            if (segment.firstPage.toLong() != next || segment.expectedCount < 1 ||
                segment.expectedCount > MAX_PAGES || segment.entry.size !in 140..PdzCompatibilityLimits.maxPdfCompressedBytes)
                throw PdzFormatException("分段页序有重叠、缺页或资源超出安全限制")
            next += segment.expectedCount
            if (next > MAX_PAGES + 1L) throw PdzFormatException("分段总页数超限")
        }
        return Plan(ordered, (next - 1).toInt())
    }

    /**
     * @param verifyPdfPages Mandatory structural PDF validation callback. It
     * must reject repaired/invalid documents and return the exact page count.
     * @param onVerifiedSegment Receives a verified PDF in canonical book order.
     * Caller must not publish any output until every bundle is verified.
     */
    fun scan(file: File, catalog: PdzCatalog, authorizedBookKey: ByteArray?,
             verifyPdfPages: (ByteArray) -> Int,
             onVerifiedSegment: (Bundle, ByteArray) -> Unit): Int {
        val plan = plan(catalog) ?: throw PdzFormatException("不是完整且仅含 PDF 分段的 PDZ")
        RandomAccessFile(file, "r").use { reader ->
            for (bundle in plan.bundles) {
                if (bundle.entry.offset < 96 || bundle.entry.offset + bundle.entry.size > reader.length())
                    throw PdzFormatException("1AH 分段资源超出 PDZ 边界")
                val raw = ByteArray(bundle.entry.size.toInt())
                reader.seek(bundle.entry.offset)
                reader.readFully(raw)
                val clear = if (raw.size >= 2 && raw[0] == 'H'.code.toByte() && raw[1] == 'H'.code.toByte()) {
                    raw
                } else {
                    val key = authorizedBookKey ?: throw PdzFormatException("缺少已验证的 PDZ 外层授权上下文")
                    OuterPageRecovery.recover(raw,key)
                }
                val decoded = OneAhPdfRecovery.recover(clear)
                val count = verifyPdfPages(decoded.pdfBytes)
                if (count != bundle.expectedCount)
                    throw PdzFormatException("${bundle.entry.name} 的 PDF 实际 $count 页，与目录承诺 ${bundle.expectedCount} 页不符")
                onVerifiedSegment(bundle,decoded.pdfBytes)
            }
        }
        return plan.totalPages
    }
}
