package org.openssreader.android.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** CI / JVM unit tests. Real Android PDF rendering is covered by device tests. */
class PdzCompatibility12Test {
    private fun catalog(vararg names: String): PdzCatalog = PdzCatalog(
        names.mapIndexed { i, n -> PdzEntry(n, 96L + 200 * i, 200) }, 96L + 200 * names.size)
    private fun fixture(name: String): File = File.createTempFile("osr12-", ".tmp").apply {
        deleteOnExit()
        PdzCompatibility12Test::class.java.classLoader!!.getResourceAsStream(name)!!.use { stream ->
            outputStream().use(stream::copyTo)
        }
    }

    @Test fun appendicesCountAsRealPhysicalPages() {
        val plan = PdzContentsReader.layout(catalog("att019.pdg", "000001.pdg", "att018.pdg"))
        assertTrue(plan.mappingAvailable)
        assertEquals(1, plan.body)
        assertEquals(2, plan.appendix)
        assertEquals(3, plan.verifiedTotalPages)
        assertEquals(listOf("000001.pdg", "att018.pdg", "att019.pdg"), plan.physicalPages!!.map { it.name })
        assertFalse(PdzContentsReader.layout(catalog("000001.pdg", "att018.pdg", "att020.pdg")).mappingAvailable)
    }

    @Test fun pdfBundlesAndAppendicesProduceContiguousPhysicalPages() {
        val plan = PdzMixedPagePlan.plan(catalog("att019.pdg", "1_2.pdg", "att018.pdg", "fow001.pdg"))!!
        assertEquals(5, plan.totalPages)
        assertEquals(2, plan.bodyPages)
        assertEquals(2, plan.appendixPages)
        assertEquals(listOf(0,1,3,4), plan.parts.map { it.firstPage })
    }

    @Test fun zeroPaddedPdfRangesAndLargeStructuralCount() {
        val plan = PdzSegmentReader.plan(catalog("000001_200000.pdg", "200001_000005.pdg"))!!
        assertEquals(200005, plan.totalPages)
        assertEquals(listOf(1, 200001), plan.bundles.map { it.firstPage })
        assertTrue(PdzCompatibilityLimits.maxPdfCompressedBytes >= 64L*1024*1024)
        assertTrue(PdzCompatibilityLimits.maxPixels >= 80_000_000L)
    }

    @Test fun emptyChapterRowsAreWhitespaceTolerant() {
        assertTrue(PdzContentsReader.isWhitespaceRow("\t  \t\r".toByteArray()))
        assertFalse(PdzContentsReader.isWhitespaceRow("Title|0100".toByteArray()))
    }

    @Test fun brokenOptionalXmlDoesNotInvalidateBook() {
        val f = File.createTempFile("osr12-meta", ".bin")
        try {
            f.writeBytes(ByteArray(96) + "<!DOCTYPE meta><meta>".toByteArray())
            val cat = PdzCatalog(listOf(PdzEntry("meta.xml", 96, f.length() - 96)), f.length())
            assertNull(BookMetadataReader.readTolerant(f, cat))
            assertThrows(PdzFormatException::class.java) { BookMetadataReader.read(f, cat) }
        } finally { f.delete() }
    }

    @Test fun downloadEnvelopeAcceptsCatalogNewerThanCurrentParser() {
        val valid = fixture("synthetic_direct_jpeg.pdz")
        val modified = File.createTempFile("osr12-catalog", ".pdz")
        try {
            assertTrue(PdzDownloadEnvelope.inspect(valid) > 0)
            val bytes = valid.readBytes()
            bytes[0x1c] = 0
            modified.writeBytes(bytes)
            assertTrue(PdzDownloadEnvelope.inspect(modified) > 0)
            assertThrows(PdzFormatException::class.java) { PdzCatalogReader.read(modified) }
            bytes[bytes.lastIndex] = 0
            modified.writeBytes(bytes)
            assertThrows(PdzFormatException::class.java) { PdzDownloadEnvelope.inspect(modified) }
        } finally { valid.delete(); modified.delete() }
    }

    @Test fun cmykJpegIsKeptAsOriginalPdfDctStream() {
        val jpeg = javaClass.classLoader!!.getResourceAsStream("compat12_cmyk.jpg")!!.use { it.readBytes() }
        val info = JpegPdfInput.inspect(jpeg)
        assertEquals(4, info.channels)
        val pdf = File.createTempFile("osr12-cmyk", ".pdf")
        try {
            FileOutputStream(pdf).use { stream -> LosslessJpegPdf(stream).apply { addPage(jpeg, info); finish() } }
            val data = pdf.readBytes()
            assertTrue(data.toString(Charsets.ISO_8859_1).contains("/ColorSpace /DeviceCMYK"))
            // Production writer embeds original JPEG bytes without transcoding.
            assertTrue(data.indexOfSlice(jpeg) >= 0)
        } finally { pdf.delete() }
    }

    @Test fun partiallyOutsidePhotoIsNotDiscarded() {
        val file = fixture("synthetic_composite11_bad_rectangle.pdz")
        try {
            val entry = PdzCatalogReader.read(file).entries.first { it.name == "000001.pdg" }
            val raw = PdzCatalogReader.resource(file, entry, 4*1024*1024)
            val clear = OuterPageRecovery.recover(raw, "TESTKEY8".toByteArray())
            val page = CompositePhotoPage.decode(clear)
            assertTrue(page.photo.x < page.background.width)
            assertTrue(page.photo.x + page.photo.drawWidth > page.background.width ||
                page.photo.y + page.photo.drawHeight > page.background.height)
        } finally { file.delete() }
    }

    private fun ByteArray.indexOfSlice(needle: ByteArray): Int =
        (0..(size - needle.size).coerceAtLeast(0)).firstOrNull { pos ->
            pos + needle.size <= size && needle.indices.all { this[pos + it] == needle[it] }
        } ?: -1
}
