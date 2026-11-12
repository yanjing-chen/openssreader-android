package org.openssreader.android.core

import java.io.File
import java.io.RandomAccessFile

/** Download-stage validation, parity with Linux transfer.inspect_container.
 * This is NOT a claim that encrypted catalog entries or page images decode.
 * Those are checked during import / page conversion, without losing the PDZ.
 */
object PdzDownloadEnvelope {
    fun inspect(file: File): Long {
        RandomAccessFile(file, "r").use { input ->
            val size = input.length()
            if (size < 104L) throw PdzFormatException("下载内容不足以构成 PDZ")
            val head = ByteArray(96)
            input.readFully(head)
            if (!head.copyOfRange(0, 5).contentEquals("%pdgz".toByteArray(Charsets.US_ASCII)) ||
                !head.copyOfRange(8, 12).contentEquals("1.01".toByteArray(Charsets.US_ASCII)))
                throw PdzFormatException("下载内容不是 PDZ 1.01")
            fun u32(at: Int): Long = (0..3).fold(0L) { value, i ->
                value or ((head[at + i].toLong() and 255L) shl (8 * i))
            }
            val offset = u32(0x14)
            val catalogBytes = u32(0x18)
            if (offset < 96 || catalogBytes == 0L || catalogBytes % 20L != 0L ||
                offset + catalogBytes + 8L > size)
                throw PdzFormatException("PDZ 资源目录边界不完整")
            input.seek(offset + catalogBytes)
            val marker = ByteArray(4)
            input.readFully(marker)
            if (!marker.contentEquals("SSCT".toByteArray(Charsets.US_ASCII)))
                throw PdzFormatException("缺少 PDZ SSCT 标记")
            input.seek(size - 4L)
            input.readFully(marker)
            if (!marker.contentEquals("SSCE".toByteArray(Charsets.US_ASCII)))
                throw PdzFormatException("缺少 PDZ SSCE 结束标记")
            return catalogBytes / 20L
        }
    }
}
