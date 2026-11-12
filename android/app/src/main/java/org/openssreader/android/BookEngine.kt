package org.openssreader.android

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import org.openssreader.android.core.BookMetadataReader
import org.openssreader.android.core.BookNames
import org.openssreader.android.core.Chapter
import org.openssreader.android.core.PdzCatalogReader
import org.openssreader.android.core.PdzAuthorization
import org.openssreader.android.core.PdzSegmentReader
import org.openssreader.android.core.PdzMixedPagePlan
import org.openssreader.android.core.PdzContentsReader
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class BookSection(val title: String, val zeroBasedPage: Int)

class BookEngine(private val store: ShelfStore) {
    fun sections(book: ShelfBook): List<BookSection> {
        if (book.type != "PDZ") return emptyList()
        val src = store.raw(book)
        val catalog = PdzCatalogReader.read(src)
        val layout = PdzContentsReader.layout(catalog)
        if (!layout.mappingAvailable) return emptyList()
        val result = mutableListOf<BookSection>()
        // Keep the UI labels concise; exact zero-based page offsets stay in zeroBasedPage.
        if (layout.front > 0) result.add(BookSection("前言", 0))
        if (layout.contents > 0) result.add(BookSection("目录", layout.front))
        if (layout.body > 0) result.add(BookSection("正文", layout.front + layout.contents))
        if (layout.appendix > 0) result.add(BookSection("附页", layout.front + layout.contents + layout.body))
        return result
    }

    private fun pdfPages(pdf: File): Int = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer -> renderer.pageCount }
    }

    fun importFile(resolver: ContentResolver, uri: Uri, originalName: String): ShelfBook {
        val extension = originalName.substringAfterLast('.', "").lowercase()
        require(extension == "pdz" || extension == "pdf") { "只能导入 PDZ 或 PDF" }
        val id = UUID.randomUUID().toString()
        val destination = File(store.bookFolder, "$id.$extension")
        try {
            resolver.openInputStream(uri)?.use { src ->
                FileOutputStream(destination).use { dest -> src.copyTo(dest, 128 * 1024); dest.fd.sync() }
            } ?: error("无法读取所选文件")
            return inspect(destination, id, originalName.substringBeforeLast('.'))
        } catch (exc: Exception) { destination.delete(); throw exc }
    }
    fun inspect(file: File, id: String, fallback: String): ShelfBook {
        if (file.extension.equals("pdf", true)) {
            val pages = pdfPages(file)
            require(pages > 0) { "空 PDF" }
            return ShelfBook(id, file.name, "PDF", fallback, totalPages = pages)
        }
        // Download/import admission uses Linux's outer PDZ envelope. New or
        // unknown catalog variants may still be preserved as original PDZ;
        // decode/whole-book export must separately pass strict page checks.
        org.openssreader.android.core.PdzDownloadEnvelope.inspect(file)
        val catalog = try { PdzCatalogReader.read(file) }
                      catch (_: org.openssreader.android.core.PdzFormatException) { null }
        val info = catalog?.let { BookMetadataReader.readTolerant(file, it) }
        val pages = catalog?.let { parsed ->
            runCatching { PdzContentsReader.layout(parsed).verifiedTotalPages }.getOrNull()
        }
        return ShelfBook(
            id, file.name, "PDZ", info?.title?.ifBlank { fallback } ?: fallback,
            info?.creatorRaw.orEmpty(), info?.publisher.orEmpty(), info?.publicationYear,
            info?.ssid.orEmpty(), pages,
        )
    }
    /** Only downloaded files are automatically renamed, with G9 rule and no overwrite. */
    fun renameDownloaded(source: File, info: ShelfBook): File {
        val ssid = info.ssid.takeIf { it.matches(Regex("\\d{1,32}")) }.orEmpty()
        // A complete download may use a newer catalog variant unknown to this
        // reader. Do not turn an optional filename upgrade into a failed download.
        val metadata = runCatching { PdzCatalogReader.read(source) }.getOrNull()
            ?.let { BookMetadataReader.readTolerant(source, it) }
        // Unknown but outer-envelope-valid PDZ should still be renamed using
        // the known book:// SSID, not left with a random internal UUID.
        val targetName = if (metadata != null) BookNames.pdzName(metadata, ssid)
            else if (ssid.isNotEmpty()) "机构图书_${ssid}.pdz"
            else return source
        val ssidSuffix = if (ssid.isEmpty()) ".pdz" else "_${ssid}.pdz"
        val stem = targetName.removeSuffix(ssidSuffix)
        repeat(1000) { n ->
            val dedupe = if (n == 0) "" else "_${n + 1}"
            val suffix = "$dedupe$ssidSuffix"
            val maxPrefixBytes = 240 - suffix.toByteArray(Charsets.UTF_8).size
            val safeStem = StringBuilder()
            for (code in stem.codePoints().toArray()) {
                val char = String(Character.toChars(code))
                if ((safeStem.toString() + char).toByteArray(Charsets.UTF_8).size > maxPrefixBytes) break
                safeStem.append(char)
            }
            val name = safeStem.toString().trimEnd(' ', '.', '_').ifBlank { "未命名图书" } + suffix
            val output = File(store.bookFolder, name)
            // createNewFile is exclusive; never rename over an existing file.
            if (output.createNewFile()) {
                try {
                    source.inputStream().use { src ->
                        FileOutputStream(output).use { dst -> src.copyTo(dst, 128 * 1024); dst.fd.sync() }
                    }
                    check(source.delete()) { "重命名后无法删除临时文件" }
                    return output
                } catch (exc: Exception) { output.delete(); throw exc }
            }
        }
        return source
    }
    fun decode(book: ShelfBook, progress: (Int, Int) -> Unit): ShelfBook {
        if (book.type == "PDF") return book
        val src = store.raw(book)
        val catalog = PdzCatalogReader.read(src)
        val layout = PdzContentsReader.layout(catalog)
        val dest = File(store.pdfFolder, "${book.id}.pdf")
        // Independently derive the expected count from the original PDZ, not
        // the mutable shelf record. An incomplete or corrupt old cache is
        // never considered a successful conversion.
        val segmentPlan = PdzSegmentReader.plan(catalog)
        val mixedPlan = if (segmentPlan == null) PdzMixedPagePlan.plan(catalog) else null
        val expectedPages = segmentPlan?.totalPages ?: mixedPlan?.totalPages ?: layout.physicalPages?.size
            ?: throw IllegalStateException("PDZ 全书实际页序尚未可靠确定")
        require(expectedPages > 0) { "原始 PDZ 没有可验证书页" }
        // A scanned bookshelf may already point to a complete public PDF.
        // Reuse it in place; do NOT decode and publish a second copy.
        val existingNames = store.publicFiles.existingNames(PublicBookStorage.Kind.PDF)
        val existingPdfName = BookFilePairing.pdfFor(book.filename, book.title,
            existingNames, book.publicPdfName)
        val publicPdf = existingPdfName?.let {
            store.publicFiles.sourceForReading(PublicBookStorage.Kind.PDF, it, store.pdfFolder)
        }
        if (publicPdf?.isFile == true && runCatching { pdfPages(publicPdf) == expectedPages }.getOrDefault(false)) {
            progress(expectedPages, expectedPages)
            return book.copy(totalPages = expectedPages,
                publicPdfName = existingPdfName, decodeMessage = "")
        }
        if (dest.exists()) {
            val oldPages = try { pdfPages(dest) } catch (_: Exception) { -1 }
            if (oldPages != expectedPages) {
                check(dest.delete()) { "失效的 PDF 缓存无法清除" }
            }
        }
        if (!dest.exists()) {
            // The app-level 100% progress event is only emitted after the
            // final PDF passes Android PdfRenderer validation below.
            val priorToValidation: (Int, Int) -> Unit = { done, total ->
                progress(if (done >= total) (total - 1).coerceAtLeast(0) else done, total)
            }
            // Automatic offline local certificate recovery for legitimately
            // acquired type=0 PDZ. Keep the book key only in memory; do not
            // guess type=2 legacy Windows device identities on Android.
            val key = when (PdzAuthorization.overview(src).kind) {
                PdzAuthorization.Kind.NO_CERTIFICATE -> null
                PdzAuthorization.Kind.LOCAL_TYPE0 -> PdzAuthorization.recoverLocalKey(src)
                PdzAuthorization.Kind.LEGACY_TYPE2 -> {
                    val saved = store.appContext.getSharedPreferences("book-protocol-v1", 0)
                        .getString("device-key", null)
                        ?: throw IllegalStateException("此 PDZ 需要原下载设备的授权上下文；当前没有已验证的本机记录")
                    val ctx = InstitutionDeviceBinding.verifiedContext(saved)
                    try { PdzAuthorization.recoverLegacyWithVerifiedContext(src, ctx) }
                    finally { ctx.fill(0) }
                }
            }
            try {
                if (segmentPlan != null) {
                    PdzSegmentPdfAssembler.convert(store.appContext, src, catalog, dest, key, priorToValidation)
                } else if (mixedPlan != null) {
                    PdzMixedPdfAssembler.convert(store.appContext, src, mixedPlan, dest, key, priorToValidation)
                } else {
                    PdzDirectJpegConverter.convert(src, layout, dest, key, priorToValidation)
                }
            } finally {
                key?.fill(0)
            }
        }
        val pages = try { pdfPages(dest) } catch (exc: Exception) {
            dest.delete() // Only app-owned derived PDF cache; keep original PDZ.
            throw IllegalStateException("生成的 PDF 无法在 Android 上打开", exc)
        }
        if (pages != expectedPages) {
            dest.delete()
            throw IllegalStateException("生成的 PDF 页数 $pages 与原始 PDZ 页数 $expectedPages 不一致")
        }
        progress(pages, pages)
        return book.copy(pdfCacheFile = dest.name, totalPages = pages, decodeMessage = "")
    }
    fun chapters(book: ShelfBook): List<Chapter>? {
        if (book.type != "PDZ") return null
        val src = store.raw(book)
        val catalog = PdzCatalogReader.read(src)
        val info = BookMetadataReader.readTolerant(src, catalog)
        return PdzContentsReader.chapters(src, catalog, info, PdzContentsReader.layout(catalog))
    }
    fun export(resolver: ContentResolver, book: ShelfBook, uri: Uri, pdf: Boolean) {
        val input = if (pdf) store.pdf(book) else store.raw(book)
        requireNotNull(input) { "这本书尚未生成完整 PDF，不能导出" }
        require(input.isFile) { "导出源文件不存在" }
        resolver.openOutputStream(uri, "w")?.use { dest ->
            input.inputStream().use { src -> src.copyTo(dest, 128 * 1024) }
        } ?: error("无法写入目标文件")
    }
}
