package org.openssreader.android.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PdzCoreTest {
    private fun fixture(name: String): File {
        val url = requireNotNull(javaClass.classLoader?.getResource(name))
        return File(url.toURI())
    }
    @Test fun catalogParityWithLinuxReference() {
        val file = fixture("synthetic_direct_jpeg.pdz")
        val cat = PdzCatalogReader.read(file)
        assertEquals(7, cat.entries.size)
        assertEquals("meta.xml", cat.entries.first().name)
        assertEquals("000002.pdg", cat.entries.last().name)
    }
    @Test fun metadataAndContributorsPreserveRoles() {
        val file = fixture("synthetic_direct_jpeg.pdz")
        val meta = BookMetadataReader.read(file, PdzCatalogReader.read(file))!!
        assertEquals("合成测试：荷兰史", meta.title)
        assertEquals(listOf("著", "译", "主编"), meta.contributors.map { it.role })
        assertEquals(2024, meta.publicationYear)
        // Match Linux G9: preserve fullwidth Chinese punctuation, but sanitize ASCII ':'.
        assertEquals("合成测试：荷兰史_著-张三_译-李四_主编-王五_测试出版社_2024_12345678.pdz",
            BookNames.pdzName(meta, "12345678"))
        assertEquals("合成测试 荷兰史_著-张三_译-李四_主编-王五_测试出版社_2024_12345678.pdz",
            BookNames.pdzName(meta.copy(title = "合成测试:荷兰史"), "12345678"))
    }
    @Test fun chapterMappingFromContentsDat() {
        val file = fixture("synthetic_direct_jpeg.pdz")
        val cat = PdzCatalogReader.read(file)
        val layout = PdzContentsReader.layout(cat)
        val chapters = PdzContentsReader.chapters(file,cat,BookMetadataReader.read(file,cat),layout)!!
        assertEquals(3, chapters.size)
        assertEquals(4, layout.verifiedTotalPages)
        assertEquals(3, chapters.last().documentPageZeroBased)
    }
    @Test fun truncatedAndWrongMagicAreRejected() {
        val file = fixture("synthetic_direct_jpeg.pdz")
        val bad = Files.createTempFile("osr-bad", ".pdz").toFile()
        try {
            bad.writeBytes(file.readBytes().copyOfRange(0,64))
            assertThrows(PdzFormatException::class.java) { PdzCatalogReader.read(bad) }
        } finally { bad.delete() }
    }
    @Test fun filenamesAreUtf8ByteBoundedAndNoGuessRoles() {
        val meta = BookMetadata(title="书".repeat(150),
            contributors = listOf(Contributor("作者甲", null), Contributor("作者乙", "编译")))
        val name = BookNames.pdzName(meta,"12345678")
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 240)
        assertTrue(name.endsWith("_12345678.pdz"))
        assertEquals(listOf(null, "编译"), meta.contributors.map { it.role })
        assertNull(BookMetadataReader.year("1645.01"))
    }
    @Test fun unsafeSsidIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BookNames.pdzName(BookMetadata(title="A"), "../foo")
        }
    }
}
