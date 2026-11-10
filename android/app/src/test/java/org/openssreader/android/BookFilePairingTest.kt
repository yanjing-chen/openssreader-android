package org.openssreader.android

import org.junit.Assert.*
import org.junit.Test

class BookFilePairingTest {
    @Test fun existingIndexWinsAndNoInventedPdf() {
        val all = listOf("Book_作者_2024.pdf", "同名标题.pdf")
        assertEquals("Book_作者_2024.pdf", BookFilePairing.pdfFor(
            "Book_作者_2024.pdz", "同名标题", all, "Book_作者_2024.pdf"))
        assertNull(BookFilePairing.pdfFor("a.pdz", "没有对应PDF", all))
    }
    @Test fun supportsG9NamesAndLegacyTitleOnlyPdf() {
        assertEquals("Book_作者_2024.pdf", BookFilePairing.pdfFor(
            "Book_作者_2024.pdz", "Book", listOf("Book_作者_2024.pdf")))
        assertEquals("文学鉴赏与写作.pdf", BookFilePairing.pdfFor(
            "文学鉴赏与写作_译-李四_2023.pdz", "文学鉴赏与写作", listOf("文学鉴赏与写作.pdf")))
    }
    @Test fun doesNotFuzzyMatchDifferentBooks() {
        assertNull(BookFilePairing.pdfFor("荷兰史2.pdz", "荷兰史2", listOf("荷兰史.pdf")))
    }
}
