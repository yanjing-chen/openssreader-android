package org.openssreader.android

import android.graphics.BitmapFactory
import org.openssreader.android.core.Hh04JpegDecoder
import org.openssreader.android.core.Hh00EmbeddedImage
import org.openssreader.android.core.BwPdgDecoder
import org.openssreader.android.core.CompositePhotoPage
import org.openssreader.android.core.OuterPageRecovery
import org.openssreader.android.core.JpegPdfInput
import org.openssreader.android.core.LosslessJpegPdf
import org.openssreader.android.core.PageLayout
import org.openssreader.android.core.PdzEntry
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Strict JPEG, HH/04H, HH/02H, HH/11H and one-photo bilevel/JPEG composites.
 * Only verified in-memory bookkeys may be used for encrypted page prefixes;
 * BookEngine handles the separate local-certificate authorization stage.
 * No incomplete PDF, invented blank images, dropped photo layers or source mutation.
 */
class UnsupportedPdzPages(message: String) : IllegalStateException(message)
object PdzDirectJpegConverter {
    private const val MAX_PAGE_SIZE = 64L * 1024L * 1024L
    private fun readPage(source: RandomAccessFile, entry: PdzEntry): ByteArray {
        if (entry.size !in 20..MAX_PAGE_SIZE || entry.size > Int.MAX_VALUE)
            throw UnsupportedPdzPages("PDG 文件尺寸超过安全限制")
        val data = ByteArray(entry.size.toInt())
        source.seek(entry.offset)
        source.readFully(data)
        return data
    }
    private sealed interface PageContent {
        data class Jpeg(val bytes:ByteArray,val info:JpegPdfInput.Info):PageContent
        data class Bilevel(val page:BwPdgDecoder.Page):PageContent
        data class Composite(val page:CompositePhotoPage.Page):PageContent
    }
    private fun decodedPage(raw:ByteArray,bookKey:ByteArray?):PageContent {
        // Never guess an outer key. No book key means explicit rejection.
        val alreadyClear=raw.size>=2 && (
            (raw[0]=='H'.code.toByte() && raw[1]=='H'.code.toByte()) ||
            ((raw[0].toInt() and 255)==255 && (raw[1].toInt() and 255)==216))
        val data=if(alreadyClear)raw else if(bookKey!=null)OuterPageRecovery.recover(raw,bookKey)
            else throw UnsupportedPdzPages("缺少已验证的图书授权上下文，外层 PDG 尚不能恢复")
        return decodeClearPage(data, allowHh00 = true)
    }
    private fun decodeClearPage(data: ByteArray, allowHh00: Boolean): PageContent {
        if (allowHh00 && Hh00EmbeddedImage.accepts(data)) {
            val nested = Hh00EmbeddedImage.unpack(data)
            return decodeClearPage(nested, allowHh00 = false)
        }
        if (data.size>=4 && (data[0].toInt() and 255)==255 &&
            (data[1].toInt() and 255)==216)
            return PageContent.Jpeg(data,JpegPdfInput.inspect(data))
        if(Hh04JpegDecoder.accepts(data)) {
            val page=Hh04JpegDecoder.decode(data)
            return PageContent.Jpeg(page.data,JpegPdfInput.inspect(page.data,page.width,page.height))
        }
        if(CompositePhotoPage.accepts(data)) return PageContent.Composite(CompositePhotoPage.decode(data))
        if(BwPdgDecoder.accepts(data)) return PageContent.Bilevel(BwPdgDecoder.decode(data))
        throw UnsupportedPdzPages("未知 PDG 页面编码；支持 JPEG、HH/00H、HH/04H、HH/02H、HH/11H 和经验证的单照片复合图层")
    }
    /** Android must successfully decode the entire original JPEG before PDF publication. */
    private fun validateJpeg(jpeg:ByteArray, metadata:JpegPdfInput.Info, pageNo:Int) {
        // Validate the complete JPEG on Android, not just SOF/EOI.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth != metadata.width || bounds.outHeight != metadata.height)
            throw UnsupportedPdzPages("第 ${pageNo} 页 Android JPEG 尺寸校验失败")
        // Verify decode without ever allocating an 80-Mpixel ARGB bitmap.
        // Sampling changes only the validation bitmap, NEVER the JPEG in PDF.
        val pixels = metadata.width.toLong() * metadata.height
        val sample = when {
            pixels > 32_000_000L -> 8
            pixels > 8_000_000L -> 4
            pixels > 2_000_000L -> 2
            else -> 1
        }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw UnsupportedPdzPages("第 ${pageNo} 页 JPEG 图片解码失败")
        try {
            if (bitmap.width < 1 || bitmap.height < 1)
                throw UnsupportedPdzPages("第 ${pageNo} 页图片尺寸不合法")
        } finally { bitmap.recycle() }
    }
    fun convert(source: File, layout: PageLayout, destination: File,
                /** Ephemeral 8-byte key from a *verified* authorized context. */
                pageBookKey: ByteArray? = null, progress: (Int, Int) -> Unit): Int {
        val pages = layout.physicalPages ?: throw UnsupportedPdzPages("图书页序尚不能可靠确定，拒绝输出不完整 PDF")
        return convertPages(source, pages, destination, pageBookKey, progress)
    }

    /** Verified ordered physical-page selection, used to create temporary
     * run PDFs for hybrid 1AH/individual-PDG books. No unknown page is skipped. */
    fun convertPages(source: File, pages: List<PdzEntry>, destination: File,
                     pageBookKey: ByteArray? = null, progress: (Int, Int) -> Unit): Int {
        if (pages.isEmpty() || pages.size > 100_000)
            throw UnsupportedPdzPages("图书页数超出支持范围")
        if (destination.exists()) throw UnsupportedPdzPages("目标 PDF 缓存已存在，拒绝覆盖")
        // Preflight the full book before creating a temporary PDF.
        RandomAccessFile(source, "r").use { input ->
            pages.forEachIndexed { i, entry ->
                try { decodedPage(readPage(input, entry),pageBookKey) }
                catch (exc: Exception) {
                    throw UnsupportedPdzPages("预检查第 ${i + 1}/${pages.size} 页失败：${exc.message}")
                }
            }
        }
        val temp = File(destination.parentFile, "${destination.name}.${UUID.randomUUID()}.partial")
        try {
            RandomAccessFile(source, "r").use { input ->
                FileOutputStream(temp).use { output ->
                    val pdf = LosslessJpegPdf(output)
                    for ((i, entry) in pages.withIndex()) {
                        when(val content=decodedPage(readPage(input, entry),pageBookKey)) {
                            is PageContent.Bilevel -> {
                                pdf.addBilevelPage(content.page)
                                if (i + 1 < pages.size) progress(i + 1, pages.size)
                            }
                            is PageContent.Composite -> {
                                validateJpeg(content.page.photo.jpeg,content.page.photo.info,i+1)
                                pdf.addCompositePage(content.page)
                                if (i + 1 < pages.size) progress(i + 1, pages.size)
                            }
                            is PageContent.Jpeg -> {
                                val (jpeg,metadata)=content.bytes to content.info
                                validateJpeg(jpeg,metadata,i+1)
                                pdf.addPage(jpeg, metadata)
                                if (i + 1 < pages.size) progress(i + 1, pages.size)
                            }
                        }
                    }
                    pdf.finish()
                    output.fd.sync()
                }
            }
            if (destination.exists() || !temp.renameTo(destination))
                throw UnsupportedPdzPages("PDF 缓存已存在或无法原子发布")
            // 100% means the complete PDF exists at its final published path.
            // All earlier callbacks are strictly below 100%.
            progress(pages.size, pages.size)
        } catch (exc: Throwable) {
            temp.delete()
            throw exc
        }
        return pages.size
    }
}
