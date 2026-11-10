package org.openssreader.android

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.openssreader.android.core.OneAhPdfRecovery
import org.openssreader.android.core.OuterPageRecovery
import org.openssreader.android.core.PdzCatalogReader
import org.openssreader.android.core.PdzMixedPagePlan
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Hybrid PDZ: numbered individual JPEG/HH/02H/11H/composite pages interleaved
 * with verified 1AH multi-page PDF segments. Never skip unknown resources or
 * flatten PDF page objects. No finished PDF is published before full validation.
 *
 * The input plan has independently proven a complete, non-overlapping order;
 * each fragment is parsed with both PDFBox and Android PdfRenderer.
 */
object PdzMixedPdfAssembler {
    private const val MAX_FRAGMENTS = 1_000
    private const val MAX_TEMP_BYTES = 768L * 1024L * 1024L

    private fun androidPages(pdf: File): Int =
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }

    private fun checkedPages(pdf: File, expected: Int): Int {
        val pdfBoxPages = PDDocument.load(pdf, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            if (document.isEncrypted) throw UnsupportedPdzPages("PDF 分段不允许二次加密")
            document.numberOfPages
        }
        if (pdfBoxPages != expected || pdfBoxPages < 1 || androidPages(pdf) != expected)
            throw UnsupportedPdzPages("PDF 页面校验失败：应有 $expected 页，PDFBox 得到 $pdfBoxPages 页")
        return expected
    }

    fun convert(context: Context, source: File, plan: PdzMixedPagePlan.Plan,
                destination: File, authorizedBookKey: ByteArray?,
                progress: (Int, Int) -> Unit): Int {
        if (destination.exists()) throw UnsupportedPdzPages("PDF 缓存已存在，不允许覆盖")
        PDFBoxResourceLoader.init(context.applicationContext)
        val parent = destination.parentFile ?: throw UnsupportedPdzPages("缺少 PDF 保存目录")
        if (!parent.isDirectory && !parent.mkdirs()) throw UnsupportedPdzPages("无法创建 PDF 保存目录")
        val work = File(parent, ".mixed-${UUID.randomUUID()}")
        if (!work.mkdir()) throw UnsupportedPdzPages("无法建立混合图书临时目录")
        val pending = File(parent, ".${destination.name}.${UUID.randomUUID()}.partial")
        val sources = ArrayList<File>()
        val total = plan.totalPages
        var done = 0
        var bytes = 0L
        var nextFile = 0
        fun record(file: File, count: Int) {
            checkedPages(file, count)
            bytes += file.length()
            if (bytes > MAX_TEMP_BYTES) throw UnsupportedPdzPages("混合图书临时文件超出磁盘安全上限")
            sources.add(file)
            if (sources.size > MAX_FRAGMENTS) throw UnsupportedPdzPages("混合 PDF 片段过多")
            done += count
            progress(minOf(done, total - 1), total)
        }
        val pendingSingles = ArrayList<org.openssreader.android.core.PdzEntry>()
        fun flushSingles() {
            if (pendingSingles.isEmpty()) return
            val first = (done + 1)
            val fragment = File(work, "part-${(nextFile++).toString().padStart(5, '0')}.pdf")
            try {
                PdzDirectJpegConverter.convertPages(source, pendingSingles.toList(), fragment,
                    authorizedBookKey) { n, _ -> progress(minOf(done + n, total - 1), total) }
                record(fragment, pendingSingles.size)
            } catch (e: Exception) {
                throw UnsupportedPdzPages("第 $first 页起的独立 PDG 处理失败：${e.message}")
            }
            pendingSingles.clear()
        }
        try {
            for (part in plan.parts) {
                if (part.firstPage != done + pendingSingles.size)
                    throw UnsupportedPdzPages("混合图书页序在转换时发生不一致")
                when (part) {
                    is PdzMixedPagePlan.Part.Single -> pendingSingles.add(part.entry)
                    is PdzMixedPagePlan.Part.PdfBundle -> {
                        flushSingles()
                        val fragment = File(work, "part-${(nextFile++).toString().padStart(5, '0')}.pdf")
                        try {
                            val raw = PdzCatalogReader.resource(source, part.entry, 64 * 1024 * 1024)
                            val clear = if (raw.size >= 2 && raw[0] == 'H'.code.toByte() &&
                                raw[1] == 'H'.code.toByte()) raw else {
                                val key = authorizedBookKey
                                    ?: throw UnsupportedPdzPages("缺少分段 ${part.entry.name} 的有效授权上下文")
                                OuterPageRecovery.recover(raw, key)
                            }
                            val pdf = OneAhPdfRecovery.recover(clear).pdfBytes
                            FileOutputStream(fragment).use { output ->
                                output.write(pdf)
                                output.fd.sync()
                            }
                            record(fragment, part.pageCount)
                        } catch (e: Exception) {
                            throw UnsupportedPdzPages("第 ${part.firstPage + 1} 页 PDF 分段 ${part.entry.name} 失败：${e.message}")
                        }
                    }
                }
            }
            flushSingles()
            if (done != total || sources.isEmpty())
                throw UnsupportedPdzPages("混合图书未覆盖全部 $total 页")

            val merger = PDFMergerUtility()
            for (fragment in sources) merger.addSource(fragment)
            merger.destinationFileName = pending.absolutePath
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            if (!pending.isFile || pending.length() < 128L)
                throw UnsupportedPdzPages("PDF 合并没有生成有效文件")
            checkedPages(pending, total)
            if (destination.exists() || !pending.renameTo(destination))
                throw UnsupportedPdzPages("PDF 合并结果无法安全发布")
            progress(total, total)
            return total
        } catch (failure: Throwable) {
            pending.delete()
            throw failure
        } finally {
            work.deleteRecursively()
        }
    }
}
