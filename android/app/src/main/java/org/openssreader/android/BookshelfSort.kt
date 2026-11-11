package org.openssreader.android

import java.text.Collator
import java.util.Locale

/** Order of books in both grid and list views, backed by one persistent preference. */
enum class BookshelfSort(val title: String, val concise: String) {
    ADDED_ASC("按添加日期升序（最早在前）", "添加日期 ↑"),
    ADDED_DESC("按添加日期降序（最新在前）", "添加日期 ↓"),
    AUTHOR_ASC("按作者升序（A → Z）", "作者 ↑"),
    AUTHOR_DESC("按作者降序（Z → A）", "作者 ↓"),
    TITLE_ASC("按书名升序（A → Z）", "书名 ↑"),
    TITLE_DESC("按书名降序（Z → A）", "书名 ↓"),
    YEAR_ASC("按出版年份升序（最早在前）", "出版年份 ↑"),
    YEAR_DESC("按出版年份降序（最新在前）", "出版年份 ↓");

    companion object {
        fun parse(value: String?): BookshelfSort? = entries.firstOrNull { it.name == value }
    }
}

object BookshelfSorting {
    fun sorted(books: List<ShelfBook>, mode: BookshelfSort): List<ShelfBook> {
        val chinese = Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY }
        fun compareText(a: String, b: String): Int = chinese.compare(a.trim(), b.trim())
        fun named(field: (ShelfBook) -> String, descending: Boolean) = Comparator<ShelfBook> { a, b ->
            val left = field(a); val right = field(b)
            when {
                left.isBlank() && right.isBlank() -> 0
                left.isBlank() -> 1 // Unknown authors always follow known authors.
                right.isBlank() -> -1
                descending -> compareText(right, left)
                else -> compareText(left, right)
            }
        }
        fun dated(book: ShelfBook): Long = if (book.addedAt > 0L) book.addedAt
            else Long.MIN_VALUE / 2 + book.legacyOrder.coerceAtLeast(0)
        val comparator = when (mode) {
            BookshelfSort.ADDED_ASC -> compareBy<ShelfBook> { dated(it) }
            BookshelfSort.ADDED_DESC -> compareByDescending<ShelfBook> { dated(it) }
            BookshelfSort.AUTHOR_ASC -> named({ it.creator }, false)
            BookshelfSort.AUTHOR_DESC -> named({ it.creator }, true)
            BookshelfSort.TITLE_ASC -> named({ it.title }, false)
            BookshelfSort.TITLE_DESC -> named({ it.title }, true)
            BookshelfSort.YEAR_ASC, BookshelfSort.YEAR_DESC -> Comparator { a, b ->
                when {
                    a.year == null && b.year == null -> 0
                    a.year == null -> 1
                    b.year == null -> -1
                    mode == BookshelfSort.YEAR_DESC -> b.year.compareTo(a.year)
                    else -> a.year.compareTo(b.year)
                }
            }
        }
        // Stable sort retains shelf order for equal keys, including unknown dates.
        return books.sortedWith(comparator)
    }
}

/** Gives each grid card its requested width rather than stretching to fill the cell. */
object BookshelfGridSizing {
    fun columns(availableWidthDp: Float, coverWidthDp: Int, spacingDp: Float = 10f): Int {
        if (!availableWidthDp.isFinite() || availableWidthDp <= 0f) return 1
        val target = coverWidthDp.coerceIn(112, 240).toFloat()
        return ((availableWidthDp + spacingDp) / (target + spacingDp)).toInt().coerceIn(1, 12)
    }
}
