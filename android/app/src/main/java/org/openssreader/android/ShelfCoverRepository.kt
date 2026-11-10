package org.openssreader.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import org.openssreader.android.core.Hh00EmbeddedImage
import org.openssreader.android.core.Hh04JpegDecoder
import org.openssreader.android.core.PdzCatalog
import org.openssreader.android.core.PdzCatalogReader
import org.openssreader.android.core.PdzContentsReader
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * High-density bookshelf artwork. This cache is completely separate from original
 * PDZ files, exported PDFs, and the persistent bookshelf index.
 *
 * Preferred source: the original PDZ cover at its available source resolution.
 * Only when no usable cover resource exists do we fall back to the first page
 * of a decoded PDF. A direct, unencrypted first-page JPEG/HH04 can be used
 * while PDZ conversion runs. Never confuse interior pages with the real cover.
 */
internal class ShelfCoverRepository(private val store: ShelfStore) {
    companion object {
        private const val MAX_COVER_RESOURCE = 16 * 1024 * 1024
        private const val MAX_PDG_RESOURCE = 20 * 1024 * 1024
        private const val MAX_BITMAP_PIXELS = 4_500_000L
        private const val MAX_BITMAP_EDGE = 3_000
        private const val MAX_DISK_CACHE_BYTES = 96L * 1024 * 1024
        private const val CACHE_VERSION = "hd-cover-v2"
    }

    private val cacheFolder = File(store.appContext.cacheDir, "shelf-covers-hd").apply { mkdirs() }
    // Kept small; composables own the bitmaps visible on screen.
    private val memory = object : LruCache<String, Bitmap>(24 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.byteCount.toLong() / 1024L).coerceAtLeast(1L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun load(book: ShelfBook, requestedWidthPx: Int): Bitmap? {
        val targetWidth = requestedWidthPx.coerceIn(800, 1600)
        val raw = runCatching { store.raw(book) }.getOrNull()?.takeIf { it.isFile }
        val pdf = runCatching { store.pdf(book) }.getOrNull()?.takeIf { it.isFile }
        val key = keyFor(book, raw, pdf, targetWidth)
        memory.get(key)?.let { return it }
        val cached = File(cacheFolder, "$key.png")
        if (cached.isFile) {
            val bitmap = runCatching { BitmapFactory.decodeFile(cached.absolutePath) }.getOrNull()
            if (bitmap != null && bitmap.width > 0 && bitmap.height > 0) {
                cached.setLastModified(System.currentTimeMillis())
                memory.put(key, bitmap)
                return bitmap
            }
            cached.delete() // Corrupt app-private cache only.
        }
        val bitmap = runCatching { renderCover(book, raw, pdf, targetWidth) }.getOrNull() ?: return null
        memory.put(key, bitmap)
        writeCache(cached, bitmap)
        return bitmap
    }

    private fun keyFor(book: ShelfBook, raw: File?, pdf: File?, width: Int): String {
        fun stamp(file: File?): String = if (file == null) "none" else
            "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        val data = "$CACHE_VERSION|${book.id}|${book.type}|$width|${stamp(raw)}|${stamp(pdf)}"
        return MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun renderCover(book: ShelfBook, raw: File?, pdf: File?, target: Int): Bitmap? {
        if (book.type == "PDF") return pdf?.let { renderPdfFirstPage(it, target) }
        if (book.type != "PDZ") return null
        val catalog = raw?.let { runCatching { PdzCatalogReader.read(it) }.getOrNull() }
        val native = if (raw != null && catalog != null) embeddedCover(raw, catalog, target) else null
        // The real embedded cover always wins: a sharper *interior* page is
        // not necessarily the book cover. Do not silently swap their meaning.
        if (native != null) return native
        val page = pdf?.let { renderPdfFirstPage(it, target) }
        if (page != null) return page
        return if (raw != null && catalog != null) directFirstPage(raw, catalog, target) else null
    }

    private fun embeddedCover(source: File, catalog: PdzCatalog, target: Int): Bitmap? {
        val entry = catalog.entries.firstOrNull { it.name.lowercase() in
            setOf("cover.jpg", "cover.jpeg", "cover.png", "cover.webp") } ?: return null
        val bytes = runCatching { PdzCatalogReader.resource(source, entry, MAX_COVER_RESOURCE) }.getOrNull()
            ?: return null
        return decodeBounded(bytes, target)
    }

    /** This is deliberately restricted to formats already recognized by the decoder.
     * Do not guess authorization keys or misinterpret encrypted PDG bytes.
     */
    private fun directFirstPage(source: File, catalog: PdzCatalog, target: Int): Bitmap? {
        val first = runCatching { PdzContentsReader.layout(catalog).physicalPages?.firstOrNull() }
            .getOrNull() ?: return null
        val raw = runCatching { PdzCatalogReader.resource(source, first, MAX_PDG_RESOURCE) }.getOrNull()
            ?: return null
        val data = if (Hh00EmbeddedImage.accepts(raw)) {
            runCatching { Hh00EmbeddedImage.unpack(raw) }.getOrNull() ?: return null
        } else raw
        val jpeg = when {
            data.size >= 2 && (data[0].toInt() and 255) == 255 && (data[1].toInt() and 255) == 216 -> data
            Hh04JpegDecoder.accepts(data) -> runCatching { Hh04JpegDecoder.decode(data).data }.getOrNull()
            else -> null
        } ?: return null
        return decodeBounded(jpeg, target)
    }

    private fun decodeBounded(bytes: ByteArray, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth < 1 || bounds.outHeight < 1) return null
        var sample = 1
        val idealMaxEdge = min(MAX_BITMAP_EDGE, target * 2)
        while (sample < 128 && (
            maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > idealMaxEdge ||
            (bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > MAX_BITMAP_PIXELS
        )) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    private fun renderPdfFirstPage(file: File, targetWidth: Int): Bitmap? {
        return try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount < 1) return null
                renderer.openPage(0).use { page ->
                    if (page.width < 1 || page.height < 1) return null
                    val widthScale = targetWidth.toDouble() / page.width
                    val edgeScale = MAX_BITMAP_EDGE.toDouble() / maxOf(page.width, page.height)
                    val pixelScale = kotlin.math.sqrt(MAX_BITMAP_PIXELS.toDouble() /
                        (page.width.toDouble() * page.height.toDouble()))
                    val scale = min(widthScale, min(edgeScale, pixelScale))
                    val width = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val height = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    } catch (ex: Exception) {
                        bitmap.recycle()
                        throw ex
                    }
                }
            }
        }
        } catch (_: Exception) { null }
    }

    private fun writeCache(file: File, bitmap: Bitmap) {
        // PNG preserves line art, small cover text, and photographic detail
        // without an additional lossy JPEG encoding pass.
        val temp = runCatching { File.createTempFile(".cover-", ".part", cacheFolder) }
            .getOrNull() ?: return
        try {
            FileOutputStream(temp).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) return
                output.fd.sync()
            }
            if (temp.length() in 1..(25L * 1024 * 1024)) {
                if (!temp.renameTo(file)) return
                trimDiskCache()
            }
        } catch (_: Exception) {
            // Cache write failure must never prevent showing a decoded cover.
        } finally { temp.delete() }
    }

    private fun trimDiskCache() {
        val items = cacheFolder.listFiles()?.filter { it.isFile && it.extension == "png" }
            ?.sortedBy { it.lastModified() } ?: return
        var total = items.sumOf { it.length() }
        for (item in items) {
            if (total <= MAX_DISK_CACHE_BYTES) break
            val bytes = item.length()
            if (item.delete()) total -= bytes
        }
    }
}
