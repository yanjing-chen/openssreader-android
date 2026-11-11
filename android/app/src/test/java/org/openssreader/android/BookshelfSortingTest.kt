package org.openssreader.android

import org.junit.Assert.*
import org.junit.Test

class BookshelfSortingTest {
    private val books = listOf(
        ShelfBook("older", "older.pdf", "PDF", "Beta", "张三", year = 2020, addedAt = 100L),
        ShelfBook("newer", "newer.pdf", "PDF", "Alpha", "李四", year = 2024, addedAt = 300L),
        ShelfBook("middle", "middle.pdf", "PDF", "Charlie", "王五", year = 2022, addedAt = 200L)
    )
    private fun ids(mode: BookshelfSort) = BookshelfSorting.sorted(books, mode).map { it.id }

    @Test fun allEightSortDirections() {
        assertEquals(8, BookshelfSort.entries.size)
        assertEquals(listOf("older", "middle", "newer"), ids(BookshelfSort.ADDED_ASC))
        assertEquals(listOf("newer", "middle", "older"), ids(BookshelfSort.ADDED_DESC))
        assertEquals(listOf("newer", "older", "middle"), ids(BookshelfSort.TITLE_ASC))
        assertEquals(listOf("middle", "older", "newer"), ids(BookshelfSort.TITLE_DESC))
        assertEquals(listOf("newer", "middle", "older"), ids(BookshelfSort.YEAR_DESC))
        assertEquals(listOf("older", "middle", "newer"), ids(BookshelfSort.YEAR_ASC))
        // Chinese locale collation: 李, 王, 张.
        assertEquals(listOf("newer", "middle", "older"), ids(BookshelfSort.AUTHOR_ASC))
        assertEquals(listOf("older", "middle", "newer"), ids(BookshelfSort.AUTHOR_DESC))
    }

    @Test fun legacyWithoutTimestampStaysOrderedAndMissingYearsGoLast() {
        val old = listOf(
            ShelfBook("old1", "a.pdf", "PDF", "旧书一", year = 2010, legacyOrder = 2L),
            ShelfBook("old2", "b.pdf", "PDF", "旧书二", year = null, legacyOrder = 1L),
            ShelfBook("new", "c.pdf", "PDF", "新书", year = 2025, addedAt = 999L)
        )
        assertEquals(listOf("new", "old1", "old2"),
            BookshelfSorting.sorted(old, BookshelfSort.ADDED_DESC).map { it.id })
        assertEquals(listOf("old2", "old1", "new"),
            BookshelfSorting.sorted(old, BookshelfSort.ADDED_ASC).map { it.id })
        assertEquals("old2", BookshelfSorting.sorted(old, BookshelfSort.YEAR_ASC).last().id)
        assertEquals("old2", BookshelfSorting.sorted(old, BookshelfSort.YEAR_DESC).last().id)
    }

    @Test fun coverSliderChangesPhysicalCardWidthAndColumnCount() {
        assertEquals(2, BookshelfGridSizing.columns(332f, 112))
        assertEquals(2, BookshelfGridSizing.columns(332f, 156))
        assertEquals(1, BookshelfGridSizing.columns(332f, 240))
        assertEquals(1, BookshelfGridSizing.columns(0f, 200))
        assertEquals(1, BookshelfGridSizing.columns(Float.NaN, 200))
    }
}
