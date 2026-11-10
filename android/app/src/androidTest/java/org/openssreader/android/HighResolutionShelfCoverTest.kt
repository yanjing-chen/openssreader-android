package org.openssreader.android

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Real Android PdfRenderer/BitmapFactory tests, run by connectedDebugAndroidTest. */
@RunWith(AndroidJUnit4::class)
class HighResolutionShelfCoverTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun drawPdf(destination: File) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(300, 450, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawRect(40f, 40f, 260f, 410f,
                Paint().apply { color = Color.rgb(35, 100, 180) })
            document.finishPage(page)
            destination.outputStream().use { document.writeTo(it) }
        } finally { document.close() }
    }

    private fun sha(file: File) = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }

    @Test fun pdfFirstPageIsRenderedSharpAndCachedWithoutModifyingOriginal() {
        val store = ShelfStore(context)
        val id = UUID.randomUUID().toString()
        val pdf = File(store.bookFolder, "$id.pdf")
        drawPdf(pdf)
        try {
            val book = ShelfBook(id, pdf.name, "PDF", "Synthetic cover test")
            val before = sha(pdf)
            val repository = ShelfCoverRepository(store)
            val image = requireNotNull(repository.load(book, 1000))
            assertEquals("Render at physical-pixel width, not old 400px preview", 1000, image.width)
            assertEquals(1500, image.height)
            assertEquals(Color.rgb(35, 100, 180), image.getPixel(image.width / 2, image.height / 2))
            assertEquals(before, sha(pdf))
            val second = requireNotNull(repository.load(book, 1000))
            assertEquals(image.width, second.width)
            assertEquals(image.height, second.height)
            assertEquals("Original PDF must be immutable", before, sha(pdf))
        } finally { pdf.delete() }
    }

    @Test fun pdzWithoutEmbeddedCoverUsesFirstDecodedPdfPage() {
        val store = ShelfStore(context)
        val id = UUID.randomUUID().toString()
        val pdf = File(store.pdfFolder, "$id.pdf")
        drawPdf(pdf)
        try {
            val book = ShelfBook(id, "$id.pdz", "PDZ", "No cover resource",
                pdfCacheFile = pdf.name)
            val image = requireNotNull(ShelfCoverRepository(store).load(book, 900))
            assertEquals(900, image.width)
            assertEquals(1350, image.height)
            assertEquals(Color.rgb(35, 100, 180), image.getPixel(450, 675))
        } finally { pdf.delete() }
    }
}
