package org.openssreader.android

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.openssreader.android.core.PdzCatalog
import org.openssreader.android.core.PdzSegmentReader
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Android-specific PDF bundle assembly for fully validated 1AH segments.
 * PDFBox copies the PDF page objects; pages are NEVER rasterized.
 * This is an A1 research integration requiring Android Gradle + real-device
 * testing. Existing one-page JPEG/BW converter is intentionally untouched.
 */
object PdzSegmentPdfAssembler {
    private const val MAX_DISK_BYTES = 768L * 1024L * 1024L
    private fun actualPages(file: File): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                renderer.pageCount.takeIf { it > 0 } ?: error("PDF 分段缺少真实页面")
            }
        }

    fun convert(context: Context, source: File, catalog: PdzCatalog, destination: File,
                authorizedBookKey: ByteArray?, progress: (Int, Int) -> Unit): Int {
        val plan = PdzSegmentReader.plan(catalog)
            ?: throw UnsupportedPdzPages("图书不是完整的 PDF 分段格式")
        if (destination.exists()) throw UnsupportedPdzPages("目标 PDF 缓存已存在，不能覆盖")
        require(plan.bundles.size <= 1000) { "PDF 分段数量超过安全限制" }
        PDFBoxResourceLoader.init(context.applicationContext)
        val parent = destination.parentFile ?: error("PDF 缓存目录不存在")
        if (!parent.isDirectory && !parent.mkdirs()) error("无法创建 PDF 缓存目录")
        val tempDir = File(parent,".segments-${UUID.randomUUID()}")
        check(tempDir.mkdir()) { "无法创建私有的 PDF 分段临时目录" }
        val partial = File(parent,".${destination.name}.${UUID.randomUUID()}.partial")
        val fragments=ArrayList<File>()
        var prepared: File?=null
        var savedBytes=0L
        var completed=0
        try {
            val total = PdzSegmentReader.scan(source,catalog,authorizedBookKey,
                verifyPdfPages = { pdf ->
                    val part=File(tempDir,"${fragments.size.toString().padStart(5,'0')}.pdf")
                    FileOutputStream(part).use { dest ->
                        dest.write(pdf); dest.fd.sync()
                    }
                    savedBytes += part.length()
                    if (savedBytes > MAX_DISK_BYTES) error("分段 PDF 缓存超过磁盘安全限制")
                    val parsed = PDDocument.load(part,MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                        if (doc.isEncrypted) throw UnsupportedPdzPages("拒绝对加密 PDF 片段进行重排")
                        doc.numberOfPages
                    }
                    val platform = actualPages(part)
                    if (parsed != platform || parsed < 1)
                        throw UnsupportedPdzPages("PDF 分段页数在两种解析器之间不一致")
                    prepared = part
                    parsed
                },
                onVerifiedSegment = { bundle,_ ->
                    val part = prepared ?: error("尚未验证当前 PDF 分段")
                    fragments.add(part)
                    prepared = null
                    completed += bundle.expectedCount
                    // Don't show 100% until merging/whole-book validation has passed.
                    progress(minOf(completed,plan.totalPages - 1),plan.totalPages)
                })
            if (fragments.size != plan.bundles.size || completed != total)
                throw UnsupportedPdzPages("PDZ 分段预检未覆盖整本书")
            val merge = PDFMergerUtility()
            for (fragment in fragments) merge.addSource(fragment)
            merge.destinationFileName = partial.absolutePath
            merge.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
            if (!partial.isFile || partial.length() < 128)
                throw UnsupportedPdzPages("PDF 分段合并没有产生有效文件")
            // Verify the combined document, not merely each fragment.
            val mergedPages=PDDocument.load(partial,MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                if (doc.isEncrypted) throw UnsupportedPdzPages("合并产物 PDF 意外加密")
                doc.numberOfPages
            }
            if (mergedPages != total || actualPages(partial) != total)
                throw UnsupportedPdzPages("PDF 合并后整书页数与目录不一致")
            if (destination.exists() || !partial.renameTo(destination))
                throw UnsupportedPdzPages("目标 PDF 已存在或无法安全发布合并产物")
            progress(total,total)
            return total
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
