package org.openssreader.android.core

/**
 * Strict physical-page planner for PDZ archives mixing single-page PDGs with
 * PDF-backed `first_count.pdg` body segments. Numbers inside the body section
 * are 1-based BODY page positions; `fow###` and `!#####` precede the body.
 *
 * This planner never infers page order from resource/catalog insertion order.
 * It rejects gaps, overlaps, unknown PDG names and appendix layouts whose
 * placement cannot be proven. No image data are decoded at this stage.
 */
object PdzMixedPagePlan {
    private val front = Regex("fow([0-9]{3})\\.pdg", RegexOption.IGNORE_CASE)
    private val toc = Regex("!([0-9]{5})\\.pdg", RegexOption.IGNORE_CASE)
    private val body = Regex("([0-9]{6})\\.pdg", RegexOption.IGNORE_CASE)
    private val bundle = Regex("([1-9][0-9]*)_([1-9][0-9]*)\\.pdg", RegexOption.IGNORE_CASE)
    private const val MAX_PAGES = 100_000L
    private const val MAX_PARTS = 100_000
    private const val MAX_SEGMENT = 64L * 1024L * 1024L

    sealed interface Part {
        val firstPage: Int // physical page index, zero-based
        val pageCount: Int
        data class Single(override val firstPage: Int, val entry: PdzEntry) : Part {
            override val pageCount = 1
        }
        data class PdfBundle(override val firstPage: Int, val entry: PdzEntry,
                             override val pageCount: Int) : Part
    }
    data class Plan(val parts: List<Part>, val frontPages: Int,
                    val tocPages: Int, val bodyPages: Int) {
        val totalPages: Int get() = frontPages + tocPages + bodyPages
    }

    /** Null means no hybrid page representation; it does NOT mean corrupted. */
    fun plan(catalog: PdzCatalog): Plan? {
        val pdgs = catalog.entries.filter { it.name.endsWith(".pdg", true) }
        val bundles = pdgs.filter { bundle.matches(it.name) }
        if (bundles.isEmpty() || bundles.size == pdgs.size) return null
        if (pdgs.size > MAX_PARTS) throw PdzFormatException("PDZ 混合页面资源数超限")

        // Catalogue names are untrusted. All PDGs must be accounted for exactly once.
        fun numbered(pattern: Regex, label: String): List<PdzEntry> {
            val indexed = pdgs.mapNotNull { entry ->
                val match = pattern.matchEntire(entry.name) ?: return@mapNotNull null
                val number = match.groupValues[1].toIntOrNull()
                    ?: throw PdzFormatException("$label 页码超出范围")
                number to entry
            }.sortedBy { it.first }
            indexed.forEachIndexed { i, (number, _) ->
                if (number != i + 1) throw PdzFormatException("$label 页码存在缺口或重叠")
            }
            return indexed.map { it.second }
        }
        val firstPages = numbered(front, "前言")
        val tocPages = numbered(toc, "目录")
        val bodySingles = pdgs.mapNotNull { entry ->
            val match = body.matchEntire(entry.name) ?: return@mapNotNull null
            val first = match.groupValues[1].toIntOrNull()
                ?: throw PdzFormatException("正文页码超出范围")
            first to entry
        }
        val segments = bundles.map { entry ->
            val match = bundle.matchEntire(entry.name)!!
            val first = match.groupValues[1].toIntOrNull()
                ?: throw PdzFormatException("PDF 分段起始页超出范围")
            val count = match.groupValues[2].toIntOrNull()
                ?: throw PdzFormatException("PDF 分段页数超出范围")
            if (entry.size !in 140..MAX_SEGMENT)
                throw PdzFormatException("PDF 分段大小超出支持范围")
            Triple(first, count, entry)
        }
        if (firstPages.size + tocPages.size + bodySingles.size + segments.size != pdgs.size)
            throw PdzFormatException("包含未识别的 PDG 页面，不能确定完整页序")
        if (bodySingles.isEmpty() && segments.isEmpty())
            throw PdzFormatException("缺少正文页面")

        val preface = firstPages.size.toLong() + tocPages.size
        var expected = 1L
        val bodyParts = (bodySingles.map { (at, entry) -> Triple(at, 1, entry) } + segments)
            .sortedWith(compareBy<Triple<Int,Int,PdzEntry>> { it.first }.thenBy { it.second })
        val parts = ArrayList<Part>(pdgs.size)
        firstPages.forEachIndexed { i, entry -> parts.add(Part.Single(i, entry)) }
        tocPages.forEachIndexed { i, entry -> parts.add(Part.Single(firstPages.size + i, entry)) }
        for ((first, count, entry) in bodyParts) {
            if (first.toLong() != expected || count < 1 ||
                expected + count - 1 > MAX_PAGES - preface)
                throw PdzFormatException("正文独立页和 PDF 分段之间存在缺页、重叠或超限")
            val global = (preface + expected - 1).toInt()
            if (bundle.matches(entry.name)) parts.add(Part.PdfBundle(global, entry, count))
            else parts.add(Part.Single(global, entry))
            expected += count
        }
        val total = preface + expected - 1
        if (total < 1 || total > MAX_PAGES) throw PdzFormatException("整书页数超限")
        return Plan(parts, firstPages.size, tocPages.size, (expected - 1).toInt())
    }
}
