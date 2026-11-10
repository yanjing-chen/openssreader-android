package org.openssreader.android.core

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Strictly bounded JPEG metadata: PDF /DCTDecode, never re-encode JPEG bytes.
 * Accept only 8-bit 1- or 3-channel JPEG with a scan and EOI marker.
 * A valid header is not proof of entropy integrity; Android validates the full
 * bitmap separately before publication, and manual visual comparison is needed.
 */
object JpegPdfInput {
    data class Info(val width: Int, val height: Int, val channels: Int)
    class Invalid(message: String) : IllegalArgumentException(message)
    private fun at(b: ByteArray, i: Int): Int = b[i].toInt() and 255
    fun inspect(jpeg: ByteArray, expectedWidth: Int? = null, expectedHeight: Int? = null): Info {
        if (jpeg.size < 16 || at(jpeg, 0) != 255 || at(jpeg, 1) != 216 ||
            at(jpeg, jpeg.size - 2) != 255 || at(jpeg, jpeg.size - 1) != 217) {
            throw Invalid("不是完整的 JPEG SOI/EOI 数据")
        }
        var pos = 2
        var found: Info? = null
        var hasScan = false
        while (pos + 4 < jpeg.size) {
            if (at(jpeg, pos++) != 0xff) throw Invalid("JPEG 段标记损坏")
            while (pos < jpeg.size && at(jpeg, pos) == 0xff) pos++
            if (pos >= jpeg.size) throw Invalid("JPEG 段不完整")
            val marker = at(jpeg, pos++)
            if (marker == 0xda) { hasScan = true; break }
            if (marker == 0xd9 || marker == 0x00 || marker in 0xd0..0xd7 || marker == 0x01)
                throw Invalid("JPEG 在 SOS 前出现非法标记")
            if (pos + 2 > jpeg.size) throw Invalid("JPEG 段长度缺失")
            val len = (at(jpeg, pos) shl 8) or at(jpeg, pos + 1)
            if (len < 2 || pos.toLong() + len > jpeg.size.toLong() - 2)
                throw Invalid("JPEG 段超过文件边界")
            if (marker in listOf(0xc0, 0xc1, 0xc2)) {
                if (found != null || len < 8 || at(jpeg, pos + 2) != 8)
                    throw Invalid("不支持的 JPEG 采样精度或重复尺寸标记")
                val height = (at(jpeg, pos + 3) shl 8) or at(jpeg, pos + 4)
                val width = (at(jpeg, pos + 5) shl 8) or at(jpeg, pos + 6)
                val channels = at(jpeg, pos + 7)
                if (width !in 1..20000 || height !in 1..20000 ||
                    width.toLong() * height > 80_000_000L || channels !in listOf(1, 3) ||
                    len < 8 + 3 * channels) {
                    throw Invalid("不支持的 JPEG 页尺寸或颜色通道")
                }
                found = Info(width, height, channels)
            } else if (marker in 0xc3..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc) {
                throw Invalid("不支持的 JPEG 压缩类型")
            }
            pos += len
        }
        val info = found ?: throw Invalid("JPEG 缺少可用的 SOF 尺寸")
        if (!hasScan) throw Invalid("JPEG 缺少 SOS 压缩数据")
        if (expectedWidth != null && info.width != expectedWidth ||
            expectedHeight != null && info.height != expectedHeight) {
            throw Invalid("HH/04H 页头尺寸与 JPEG 解码结果不一致")
        }
        return info
    }
}

/** Small, sequential PDF 1.4 writer. JPEG streams are preserved byte-for-byte.
 * 300 DPI controls *physical PDF page size*, not scan resolution or pixels.
 */
class LosslessJpegPdf(private val sink: FileOutputStream, private val dpi: Int = 300) {
    private val positions = arrayListOf(0L)
    private val pageRefs = arrayListOf<Int>()
    private var written = 0L
    private val catalog = newObject()
    private val pages = newObject()
    init {
        require(dpi in 72..1200)
        emit("%PDF-1.4\n%")
        emit(byteArrayOf(0xe2.toByte(), 0xe3.toByte(), 0xcf.toByte(), 0xd3.toByte()))
        emit("\n")
    }
    private fun newObject(): Int { positions.add(-1L); return positions.lastIndex }
    private fun emit(s: String) = emit(s.toByteArray(StandardCharsets.US_ASCII))
    private fun emit(bytes: ByteArray) { sink.write(bytes); written += bytes.size }
    private fun objectStart(id: Int) {
        require(positions[id] == -1L)
        positions[id] = written
        emit("$id 0 obj\n")
    }
    private fun objectEnd() = emit("\nendobj\n")
    private fun streamObject(id: Int, attributes: String, bytes: ByteArray) {
        objectStart(id)
        emit("<< $attributes /Length ${bytes.size} >>\nstream\n")
        emit(bytes)
        emit("\nendstream")
        objectEnd()
    }
    fun addPage(jpeg: ByteArray, info: JpegPdfInput.Info) {
        val im = newObject(); val content = newObject(); val page = newObject()
        val width = info.width * 72.0 / dpi
        val height = info.height * 72.0 / dpi
        val w = "%.8f".format(java.util.Locale.US, width)
        val h = "%.8f".format(java.util.Locale.US, height)
        streamObject(im, "/Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} " +
            "/ColorSpace ${if (info.channels == 1) "/DeviceGray" else "/DeviceRGB"} " +
            "/BitsPerComponent 8 /Filter /DCTDecode", jpeg)
        val operators = "q\n$w 0 0 $h 0 0 cm\n/Im0 Do\nQ\n".toByteArray(StandardCharsets.US_ASCII)
        streamObject(content, "", operators)
        objectStart(page)
        emit("<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 $w $h] " +
            "/Resources << /XObject << /Im0 $im 0 R >> >> /Contents $content 0 R >>")
        objectEnd()
        pageRefs.add(page)
    }
    /** 1-bit DeviceGray PDF XObject (0=black, 1=white), flate-only lossless.
     * A1c native 02H/11H output is already packed with row padding and is NOT
     * scaled or converted to RGB. PDF physical geometry remains 300 DPI.
     */
    fun addBilevelPage(page: BwPdgDecoder.Page) {
        val wPixels=page.width; val hPixels=page.height
        require(wPixels in 1..20000 && hPixels in 1..30000 &&
            wPixels.toLong()*hPixels<=80_000_000L &&
            page.packedWhiteOne.size.toLong()==((wPixels+7L)/8L)*hPixels)
        val compressed=ByteArrayOutputStream()
        DeflaterOutputStream(compressed,Deflater(Deflater.BEST_SPEED)).use {
            it.write(page.packedWhiteOne)
        }
        val image=newObject();val content=newObject();val pageId=newObject()
        val width="%.8f".format(java.util.Locale.US,wPixels*72.0/dpi)
        val height="%.8f".format(java.util.Locale.US,hPixels*72.0/dpi)
        streamObject(image,"/Type /XObject /Subtype /Image /Width $wPixels /Height $hPixels " +
            "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode",compressed.toByteArray())
        val commands="q\n$width 0 0 $height 0 0 cm\n/Im0 Do\nQ\n".toByteArray(StandardCharsets.US_ASCII)
        streamObject(content,"",commands)
        objectStart(pageId)
        emit("<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 $width $height] " +
            "/Resources << /XObject << /Im0 $image 0 R >> >> /Contents $content 0 R >>")
        objectEnd()
        pageRefs.add(pageId)
    }
    /** Preserve TWO separate image XObjects: original 1-bit scan plus JPEG.
     * The second image is placed with top-origin descriptor coordinates.
     * No RGB rasterization and no JPEG recompression.
     */
    fun addCompositePage(page: CompositePhotoPage.Page) {
        val bg = page.background; val photo = page.photo
        val pixels = bg.packedWhiteOne
        val pitch = (bg.width + 7L) / 8L
        require(bg.width in 1..20000 && bg.height in 1..30000 &&
            bg.width.toLong() * bg.height <= 80_000_000L &&
            pixels.size.toLong() == pitch * bg.height)
        require(photo.x >= 0 && photo.y >= 0 && photo.drawWidth > 0 && photo.drawHeight > 0 &&
            photo.x.toLong() + photo.drawWidth <= bg.width &&
            photo.y.toLong() + photo.drawHeight <= bg.height)
        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed, Deflater(Deflater.BEST_SPEED)).use {
            it.write(pixels)
        }
        val backgroundId = newObject(); val photoId = newObject()
        val contentId = newObject(); val pageId = newObject()
        fun pt(n: Int): String = "%.8f".format(java.util.Locale.US, n * 72.0 / dpi)
        val pageWidth = pt(bg.width); val pageHeight = pt(bg.height)
        streamObject(backgroundId,
            "/Type /XObject /Subtype /Image /Width ${bg.width} /Height ${bg.height} " +
            "/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode",
            compressed.toByteArray())
        streamObject(photoId,
            "/Type /XObject /Subtype /Image /Width ${photo.info.width} /Height ${photo.info.height} " +
            "/ColorSpace ${if (photo.info.channels == 1) "/DeviceGray" else "/DeviceRGB"} " +
            "/BitsPerComponent 8 /Filter /DCTDecode", photo.jpeg)
        val yBottom = bg.height - photo.y - photo.drawHeight
        val commands = ("q\n$pageWidth 0 0 $pageHeight 0 0 cm\n/Im0 Do\nQ\n" +
            "q\n${pt(photo.drawWidth)} 0 0 ${pt(photo.drawHeight)} ${pt(photo.x)} ${pt(yBottom)} cm\n/Im1 Do\nQ\n")
            .toByteArray(StandardCharsets.US_ASCII)
        streamObject(contentId, "", commands)
        objectStart(pageId)
        emit("<< /Type /Page /Parent $pages 0 R /MediaBox [0 0 $pageWidth $pageHeight] " +
            "/Resources << /XObject << /Im0 $backgroundId 0 R /Im1 $photoId 0 R >> >> " +
            "/Contents $contentId 0 R >>")
        objectEnd()
        pageRefs.add(pageId)
    }

    fun finish() {
        require(pageRefs.isNotEmpty())
        objectStart(pages)
        emit("<< /Type /Pages /Count ${pageRefs.size} /Kids [${pageRefs.joinToString(" ") { "$it 0 R" }}] >>")
        objectEnd()
        objectStart(catalog)
        emit("<< /Type /Catalog /Pages $pages 0 R >>")
        objectEnd()
        val xref = written
        emit("xref\n0 ${positions.size}\n0000000000 65535 f \n")
        for (i in 1 until positions.size) {
            val offset = positions[i]
            check(offset in 0..9_999_999_999) { "PDF XRef offset overflow" }
            emit("%010d 00000 n \n".format(java.util.Locale.US, offset))
        }
        emit("trailer\n<< /Size ${positions.size} /Root $catalog 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }
}
