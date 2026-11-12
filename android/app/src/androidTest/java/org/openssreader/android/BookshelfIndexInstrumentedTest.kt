package org.openssreader.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android's real org.json implementation, not desktop JVM android.jar stubs. */
@RunWith(AndroidJUnit4::class)
class BookshelfIndexInstrumentedTest {
    @Test fun indexRoundTripKeepsReferencesAndLabels() {
        val book = ShelfBook(
            "11111111-2222-3333-4444-555555555555", "archive.pdz", "PDZ",
            "荷兰史", "张三 著", "某出版社", 2024, "12345678", 120, 33,
            publicPdzName = "荷兰史_张三_2024_12345678.pdz",
            publicPdfName = "荷兰史_张三_2024_12345678.pdf")
        val state = BookshelfIndex.Snapshot(listOf(book), setOf(book.id),
            listOf(ShelfLabel("tag-1", "儿子用", setOf(book.id))))
        val text = BookshelfIndex.encode(state)
        assertFalse(text.contains("book://"))
        val restored = BookshelfIndex.decode(text)
        assertEquals(book.publicPdzName, restored.books.single().publicPdzName)
        assertEquals(book.publicPdfName, restored.books.single().publicPdfName)
        assertEquals(33, restored.books.single().lastPage)
        assertEquals("儿子用", restored.labels.single().title)
        assertTrue(book.id in restored.favorites)
    }
    @Test fun oneJsonPerBookRoundTripAndTagDictionary() {
        val book = ShelfBook(
            "11111111-2222-3333-4444-555555555555", "archive.pdz", "PDZ",
            "荷兰史", "张三 著", "某出版社", 2024, "12345678", 120, 33,
            publicPdzName = "荷兰史_张三_2024_12345678.pdz",
            publicPdfName = "荷兰史_张三_2024_12345678.pdf")
        val json = BookshelfIndex.encodeRecord(book, favorite = true,
            labelIds = setOf("tag-1", "tag-2"))
        val decoded = BookshelfIndex.decodeRecord(json)
        assertEquals(book.id, decoded.book.id)
        assertEquals(book.publicPdfName, decoded.book.publicPdfName)
        assertEquals(33, decoded.book.lastPage)
        assertTrue(decoded.favorite)
        assertEquals(setOf("tag-1", "tag-2"), decoded.labelIds)
        val labels = listOf(ShelfLabel("tag-1", "哈哈哈", setOf(book.id)))
        val decodedLabels = BookshelfIndex.decodeLabelDefinitions(
            BookshelfIndex.encodeLabelDefinitions(labels))
        assertEquals("哈哈哈", decodedLabels.single().title)
        assertTrue(decodedLabels.single().bookIds.isEmpty())
    }
    @Test fun jsonNameUsesExactPdzOrPdfStem() {
        val pdz = ShelfBook("11111111-2222-3333-4444-555555555555", "internal.pdz", "PDZ",
            "荷兰史", publicPdzName = "荷兰史_作者_2024_123.pdz",
            publicPdfName = "荷兰史_作者_2024_123.pdf")
        assertEquals("荷兰史_作者_2024_123.json", BookshelfIndex.recordFileName(pdz))
        val pdf = pdz.copy(type = "PDF", publicPdzName = null)
        assertEquals("荷兰史_作者_2024_123.json", BookshelfIndex.recordFileName(pdf))
        assertTrue(runCatching { BookshelfIndex.recordFileName(pdz.copy(publicPdzName = null)) }.isFailure)
    }
    @Test fun cannotRestoreEscapingFilename() {
        val text = """{"schema":1,"books":[{"type":"PDZ","publicPdzName":"../outside.pdz"}]}"""
        assertTrue(BookshelfIndex.decode(text).books.isEmpty())
    }
}
