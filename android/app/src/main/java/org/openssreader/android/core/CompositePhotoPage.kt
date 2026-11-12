package org.openssreader.android.core

/**
 * 02H/11H single embedded JPEG overlay, ported from the validated Linux
 * native_bw_pdf_export.embedded_photo descriptor at offsets 140..159.
 * It is NOT the unrelated d1a2 encrypted container. No heuristic fallback.
 */
object CompositePhotoPage {
    class Invalid(message: String): IllegalArgumentException(message)
    data class Photo(val jpeg: ByteArray, val x: Int, val y: Int,
                     val drawWidth: Int, val drawHeight: Int,
                     val info: JpegPdfInput.Info)
    data class Page(val background: BwPdgDecoder.Page, val photo: Photo)

    private fun u8(bytes: ByteArray, pos: Int): Int = bytes[pos].toInt() and 255
    private fun u16(bytes: ByteArray, pos: Int): Int = u8(bytes,pos) or (u8(bytes,pos+1) shl 8)
    private fun u32(bytes: ByteArray, pos: Int): Long =
        u16(bytes,pos).toLong() or (u16(bytes,pos+2).toLong() shl 16)

    fun accepts(bytes: ByteArray): Boolean = bytes.size >= 160 &&
        bytes[0] == 'H'.code.toByte() && bytes[1] == 'H'.code.toByte() &&
        u8(bytes,0x0f) in listOf(0x02,0x11) && u8(bytes,0x15) == 1

    fun decode(clear: ByteArray): Page {
        if (!accepts(clear)) throw Invalid("不是已识别的 HH/02H、HH/11H 单照片复合页面")
        if (u32(clear,140) != 1L) throw Invalid("复合图层数量不等于 1，不能丢失其他图层")
        val x=u16(clear,144); val y=u16(clear,146)
        val w=u16(clear,148); val h=u16(clear,150)
        val offset=u32(clear,152); val count=u32(clear,156)
        val compressedAt=u32(clear,0x18); val compressedSize=u32(clear,0x1c)
        if (w !in 1..30000 || h !in 1..30000 || count !in 32..64L*1024*1024 ||
            offset < 160L || compressedAt < 160L ||
            compressedAt+compressedSize > clear.size ||
            offset < compressedAt+compressedSize || offset+count > clear.size)
            throw Invalid("复合照片数据范围不合法或与黑白位流重叠")
        val pageW=u16(clear,0x10); val pageH=u16(clear,0x12)
        // PDF MediaBox clips the drawing; a partially outside rectangle is valid.
        // Reject only fully outside photos or impossible scan dimensions.
        if (pageW < 1 || pageH < 1 || x >= pageW || y >= pageH)
            throw Invalid("复合照片没有与扫描页面相交：page=${pageW}x$pageH, photo=($x,$y ${w}x$h)")
        val jpeg=clear.copyOfRange(offset.toInt(),(offset+count).toInt())
        val info=JpegPdfInput.inspect(jpeg)
        val bg=BwPdgDecoder.decodeVerifiedCompositeBackground(clear)
        return Page(bg,Photo(jpeg,x,y,w,h,info))
    }
}
