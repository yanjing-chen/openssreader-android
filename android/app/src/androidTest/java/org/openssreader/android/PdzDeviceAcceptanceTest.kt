package org.openssreader.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openssreader.android.core.PdzAuthorization
import org.openssreader.android.core.PdzCatalogReader
import org.openssreader.android.core.PdzContentsReader
import org.openssreader.android.core.PdzMixedPagePlan
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Device-only acceptance tests: uses the real Android PdfRenderer and the
 * real PDFBox-Android AAR (never the offline JVM stand-ins). Fixtures are
 * synthetic, contain no copyrighted pages, credentials, or network access.
 * Run: ./gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PdzDeviceAcceptanceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(name: String): File {
        val file = File(context.cacheDir, "${UUID.randomUUID()}-$name")
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun verifyAndroidPages(pdf: File, expected: Int) {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                assertEquals("Android PdfRenderer page count", expected, renderer.pageCount)
                for (pageNo in 0 until renderer.pageCount) {
                    renderer.openPage(pageNo).use { page ->
                        assertTrue("positive PDF page width", page.width > 0)
                        assertTrue("positive PDF page height", page.height > 0)
                        // Small rendering sanity check; no full-page 80MP allocation.
                        val scale = minOf(1f, 300f / maxOf(page.width, page.height))
                        val bitmap = Bitmap.createBitmap(
                            (page.width * scale).toInt().coerceAtLeast(1),
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        } finally { bitmap.recycle() }
                    }
                }
            }
        }
    }

    @Test fun verifiedType0Segmented1AhRoundTripOnAndroid() {
        val input = fixture("synthetic_oneah_segmented_type0.pdz")
        val target = File(context.cacheDir, "${UUID.randomUUID()}-merged.pdf")
        val before = sha(input.readBytes())
        PDFBoxResourceLoader.init(context)
        try {
            val catalog = PdzCatalogReader.read(input)
            val key = PdzAuthorization.recoverLocalKey(input)
            assertNotNull("type=0 certificate should recover an in-memory key", key)
            val events = mutableListOf<Pair<Int, Int>>()
            try {
                val pages = PdzSegmentPdfAssembler.convert(context, input, catalog, target, key) { done, total ->
                    if (done == total) assertTrue("100% only after final PDF publish", target.isFile)
                    events += done to total
                }
                assertEquals(3, pages)
            } finally { key?.fill(0) }
            assertEquals(3 to 3, events.last())
            assertEquals(before, sha(input.readBytes()))
            verifyAndroidPages(target, 3)
            PDDocument.load(target).use { pdf ->
                assertEquals(3, pdf.numberOfPages)
                val text = PDFTextStripper().getText(pdf)
                val a = text.indexOf("FIRST")
                val b = text.indexOf("SECOND")
                val c = text.indexOf("THIRD")
                assertTrue("merged PDF preserves ordered text", a >= 0 && a < b && b < c)
            }
        } finally { input.delete(); target.delete() }
    }

    @Test fun decoderOutputsTruePagesOnAndroidAndReportsFinalProgressOnlyAfterPublish() {
        for (name in listOf("synthetic_hh04_mixed.pdz", "synthetic_composite_both.pdz")) {
            val input = fixture(name)
            val target = File(context.cacheDir, "${UUID.randomUUID()}-pages.pdf")
            val original = sha(input.readBytes())
            try {
                val layout = PdzContentsReader.layout(PdzCatalogReader.read(input))
                val progress = mutableListOf<Pair<Int, Int>>()
                val pages = PdzDirectJpegConverter.convert(input, layout, target) { done, total ->
                    if (done == total) assertTrue("100% only after publish", target.isFile)
                    progress += done to total
                }
                assertEquals(3, pages)
                assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
                assertEquals(original, sha(input.readBytes()))
                verifyAndroidPages(target, 3)
            } finally { input.delete(); target.delete() }
        }
    }

    @Test fun brokenCachedPdfIsNotMistakenForACompletedBook() {
        val source = fixture("synthetic_hh04_mixed.pdz")
        val store = ShelfStore(context)
        val bookId = UUID.randomUUID().toString()
        val raw = File(store.bookFolder, "$bookId.pdz")
        val cached = File(store.pdfFolder, "$bookId.pdf")
        try {
            source.copyTo(raw)
            cached.writeText("%PDF-1.4\ntruncated PDF cache")
            val book = BookEngine(store).inspect(raw, bookId, "synthetic")
            val decoded = BookEngine(store).decode(book) { _, _ -> }
            assertEquals(3, decoded.totalPages)
            verifyAndroidPages(cached, 3)
        } finally { source.delete(); raw.delete(); cached.delete() }
    }

    @Test fun certificateAbsentDoesNotPublishIncompleteSegmentedPdf() {
        val input = fixture("synthetic_oneah_segmented_type0.pdz")
        val target = File(context.cacheDir, "${UUID.randomUUID()}-invalid.pdf")
        try {
            val failure = runCatching {
                PdzSegmentPdfAssembler.convert(context, input, PdzCatalogReader.read(input), target, null) { _, _ -> }
            }.exceptionOrNull()
            assertNotNull("A protected segment without authorization must fail", failure)
            assertFalse("A failed conversion cannot publish a PDF", target.exists())
        } finally { input.delete(); target.delete() }
    }
    @Test fun hybridPdfSegmentsAndSingleJpegsMergeInCorrectPageOrder() {
        PDFBoxResourceLoader.init(context)
        for (name in listOf("synthetic_hybrid_cover_and_segments.pdz",
            "synthetic_hybrid_interleaved_body.pdz")) {
            val input = fixture(name)
            val target = File(context.cacheDir, "${UUID.randomUUID()}-hybrid.pdf")
            val initialSha = sha(input.readBytes())
            try {
                val catalog = PdzCatalogReader.read(input)
                val plan = requireNotNull(PdzMixedPagePlan.plan(catalog))
                assertEquals(4, plan.totalPages)
                val events = mutableListOf<Pair<Int, Int>>()
                val key = PdzAuthorization.recoverLocalKey(input)
                try {
                    val pages = PdzMixedPdfAssembler.convert(context, input, plan, target, key) { done, all ->
                        if (done == all) assertTrue("100% only when PDF published", target.isFile)
                        events += done to all
                    }
                    assertEquals(4, pages)
                } finally { key?.fill(0) }
                assertEquals(4 to 4, events.last())
                assertEquals(initialSha, sha(input.readBytes()))
                verifyAndroidPages(target, 4)
                PDDocument.load(target).use { pdf ->
                    assertEquals(4, pdf.numberOfPages)
                    val text = PDFTextStripper().getText(pdf)
                    val first = text.indexOf("FIRST")
                    val second = text.indexOf("SECOND")
                    assertTrue("PDF-backed segment text and order preserved", first >= 0 && first < second)
                    if (name.contains("cover_and_segments")) {
                        val third = text.indexOf("THIRD")
                        assertTrue("third PDF-backed page retained", second < third)
                    }
                }
            } finally { input.delete(); target.delete() }
        }
    }

    @Test fun hybridUnknownOrCorruptedPageDoesNotPublishPdf() {
        val input = fixture("synthetic_hybrid_interleaved_body.pdz")
        val output = File(context.cacheDir, "${UUID.randomUUID()}-rejected.pdf")
        try {
            val catalog = PdzCatalogReader.read(input)
            val plan = requireNotNull(PdzMixedPagePlan.plan(catalog))
            val entry = catalog.entries.first { it.name == "000003.pdg" }
            java.io.RandomAccessFile(input, "rw").use { file ->
                file.seek(entry.offset)
                file.write(ByteArray(minOf(64L, entry.size).toInt()) { 0x7f })
            }
            val key = PdzAuthorization.recoverLocalKey(input)
            try {
                assertThrows(Exception::class.java) {
                    PdzMixedPdfAssembler.convert(context, input, plan, output, key) { _, _ -> }
                }
            } finally { key?.fill(0) }
            assertFalse("invalid hybrid page must not publish incomplete PDF", output.exists())
        } finally { input.delete(); output.delete() }
    }

}
