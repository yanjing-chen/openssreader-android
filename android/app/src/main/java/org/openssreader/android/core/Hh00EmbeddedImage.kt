package org.openssreader.android.core

/**
 * Conservative HH/00H inner-image handling from Linux
 * native_1ah_pdf_export._decode_00h_image: only exact-length,
 * independently recognizable embedded JPEG/HH pages are accepted.
 * The HH outer-prefix must have already been recovered.
 */
object Hh00EmbeddedImage {
    class Unsupported(message: String) : IllegalArgumentException(message)
    private fun u32(b: ByteArray, i: Int): Long =
        (b[i].toLong() and 255L) or ((b[i + 1].toLong() and 255L) shl 8) or
            ((b[i + 2].toLong() and 255L) shl 16) or ((b[i + 3].toLong() and 255L) shl 24)

    fun accepts(clear: ByteArray): Boolean = clear.size >= 140 &&
        clear[0] == 'H'.code.toByte() && clear[1] == 'H'.code.toByte() && clear[15] == 0.toByte()

    fun unpack(clear: ByteArray): ByteArray {
        if (!accepts(clear)) throw Unsupported("不是合法的 HH/00H 内嵌页面")
        val offset = u32(clear, 0x18)
        val length = u32(clear, 0x1c)
        if (offset < 140L || length < 4L || offset + length != clear.size.toLong() ||
            length > 64L * 1024L * 1024L) throw Unsupported("HH/00H 页面资源范围不完整")
        val payload = clear.copyOfRange(offset.toInt(), clear.size)
        val jpeg = payload.size >= 4 && (payload[0].toInt() and 255) == 255 &&
            (payload[1].toInt() and 255) == 216 && (payload[2].toInt() and 255) == 255 &&
            (payload[payload.size - 2].toInt() and 255) == 255 &&
            (payload[payload.size - 1].toInt() and 255) == 217
        if (jpeg) {
            JpegPdfInput.inspect(payload)  // independent structure check
            return payload
        }
        if (payload.size >= 140 && payload[0] == 'H'.code.toByte() &&
            payload[1] == 'H'.code.toByte() && payload[2] == 2.toByte() &&
            payload[15] != 0.toByte() &&
            (Hh04JpegDecoder.accepts(payload) || BwPdgDecoder.accepts(payload)))
            return payload
        throw Unsupported("HH/00H 内嵌编码不在已验证支持范围")
    }
}
