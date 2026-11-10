package org.openssreader.android.core

import java.security.MessageDigest

/**
 * HH/0x4A02/04H *inner* codec, ported byte-for-byte from the validated Linux
 * `openssreader/vendor/HH04/native_hh04.py` reference algorithm.
 *
 * Input is an already-clear PDG page. Many real PDZs separately encrypt an
 * OUTER prefix, which this class deliberately does NOT decrypt or guess.
 * No Windows DLL, JNI, keys, device identifiers or network access are used.
 */
object Hh04JpegDecoder {
    data class JpegPage(val data: ByteArray, val width: Int, val height: Int)
    class Unsupported(message: String) : IllegalArgumentException(message)

    private fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 0xff
    private fun u16(bytes: ByteArray, at: Int): Int = u8(bytes, at) or (u8(bytes, at + 1) shl 8)
    private fun u32(bytes: ByteArray, at: Int): Long =
        (u8(bytes, at).toLong() or (u8(bytes, at + 1).toLong() shl 8) or
            (u8(bytes, at + 2).toLong() shl 16) or (u8(bytes, at + 3).toLong() shl 24))
    private fun word(bytes: ByteArray, at: Int): Int = u32(bytes, at).toInt()
    private fun putWord(bytes: ByteArray, at: Int, value: Int) {
        for (i in 0..3) bytes[at + i] = (value ushr (i * 8)).toByte()
    }

    fun accepts(clear: ByteArray): Boolean = clear.size >= 140 &&
        clear[0] == 'H'.code.toByte() && clear[1] == 'H'.code.toByte() &&
        u8(clear, 2) == 2 && u8(clear, 3) == 0x4a &&
        u8(clear, 12) == 0x80 && u8(clear, 13) == 0 &&
        u8(clear, 14) == 0x4a && u8(clear, 15) == 4

    fun decode(clear: ByteArray): JpegPage {
        if (!accepts(clear)) throw Unsupported("不是已恢复外层数据的 HH/04H 页面")
        val width = u16(clear, 16)
        val height = u16(clear, 18)
        val start = u32(clear, 24)
        val size = u32(clear, 28)
        if (width !in 1..20000 || height !in 1..20000 ||
            width.toLong() * height > 80_000_000L ||
            start < 140 || size <= 16 || size > 64L * 1024 * 1024 ||
            start + size != clear.size.toLong()) {
            throw Unsupported("HH/04H 页头尺寸或 JPEG 数据范围不合法")
        }
        val md5 = MessageDigest.getInstance("MD5")
            .digest(clear.copyOfRange(0x40, 0x70))
        val mask = "SSREADER".toByteArray(Charsets.US_ASCII)
        for (i in mask.indices) md5[i] = (md5[i].toInt() xor mask[i].toInt()).toByte()
        val decoded = clear.copyOfRange(start.toInt(), clear.size)
        val keyA = word(md5, 0)
        val keyB = word(md5, 4)
        val keyC = word(md5, 8)
        val keyD = word(md5, 12)
        for (pos in 0 until (decoded.size / 16) * 16 step 16) {
            var v0 = word(decoded, pos)
            var v1 = word(decoded, pos + 4)
            var v2 = word(decoded, pos + 8)
            var v3 = word(decoded, pos + 12)
            var sum = 0xe3779b90u.toInt()
            repeat(16) {
                v3 -= (((v0 shl 4) + keyC) xor (sum + v0) xor ((v0 ushr 5) + keyB))
                v2 -= (((v3 shl 4) + keyA) xor (sum + v3) xor ((v3 ushr 5) + keyD))
                v1 -= (((v2 shl 4) + keyC) xor (sum + v2) xor ((v2 ushr 5) + keyD))
                val tmp = sum + v1
                sum += 0x61c88647
                v0 -= (((v1 shl 4) + keyA) xor tmp xor ((v1 ushr 5) + keyB))
            }
            putWord(decoded, pos, v0)
            putWord(decoded, pos + 4, v1)
            putWord(decoded, pos + 8, v2)
            putWord(decoded, pos + 12, v3)
        }
        if (decoded.size < 4 || u8(decoded, 0) != 0xff || u8(decoded, 1) != 0xd8 ||
            u8(decoded, 2) != 0xff || u8(decoded, decoded.size - 2) != 0xff ||
            u8(decoded, decoded.size - 1) != 0xd9) {
            throw Unsupported("HH/04H 密文未恢复出完整 JPEG，拒绝伪成功")
        }
        return JpegPage(decoded, width, height)
    }
}
