package org.openssreader.android.core

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Kotlin/Android reimplementation of vendor/engine/pdz_catalog_reader.py.
 * This is catalog decryption ONLY. It does not decode any encrypted PDG page.
 */
class PdzFormatException(message: String) : IllegalArgumentException(message)
data class PdzEntry(val name: String, val offset: Long, val size: Long)
data class PdzCatalog(val entries: List<PdzEntry>, val fileBytes: Long)

object PdzCatalogReader {
    private const val HEADER_SIZE = 96
    private const val RECORD_SIZE = 20
    private const val BLOCK_SIZE = 16
    private const val MASK = 0xffffffffL
    private const val DELTA = 0x9e3779b9L
    private val seed = "ljhd".toByteArray(StandardCharsets.US_ASCII)
    private val alphabet = "4+RTe6i#r3%9Cucv^&*()_bn2!yhj8'zI./QWE5=~tFmop[]\\a@qwBsg,OG\$fkl;0-HJKNM<`1DdYUV7L:\"ZXxP{}|AS>?".toByteArray(StandardCharsets.US_ASCII)
    private val masks = longArrayOf(0x00820208L, 0x01220208L, 0x00805038L, 0x00920208L)
    private val filename = Regex("[A-Za-z0-9!._-]{1,12}")

    private fun error(message: String): Nothing = throw PdzFormatException(message)
    private fun u32(buf: ByteArray, offset: Int): Long =
        (ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and MASK)
    private fun put32(buf: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt())
    }
    private fun round(y: Long, sum: Long, ka: Long, kb: Long): Long =
        (((((y shl 4) and MASK) + ka) and MASK) xor ((y + sum) and MASK) xor (((y shr 5) + kb) and MASK))

    private fun words(header: ByteArray): LongArray {
        if (!header.copyOfRange(0, 5).contentEquals("%pdgz".toByteArray())) error("PDZ header magic is invalid")
        if (!header.copyOfRange(8, 12).contentEquals("1.01".toByteArray())) error("Only PDZ 1.01 catalogs are currently supported")
        if ((header[13].toInt() and 0xff) != 4) error("Unsupported catalog cipher")
        val textBytes = header.copyOfRange(0x1c, 0x60).takeWhile { it.toInt() != 0 }
        if (textBytes.isEmpty() || textBytes.size > 32) error("Invalid PDZ catalog transform header")
        val scratch = ArrayList<Byte>(textBytes.size * 2)
        textBytes.forEachIndexed { i, ch ->
            val at = (((seed[i % 4].toInt() and 0xff) * i) % 96)
            if (at >= alphabet.size) error("Transform alphabet index out of range")
            scratch.add(ch)
            scratch.add(alphabet[at])
        }
        val expanded = ByteArray(32) { scratch[it % scratch.size] }
        val hex = expanded.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        val digest = MessageDigest.getInstance("MD5").digest(hex.toByteArray(StandardCharsets.US_ASCII))
        return LongArray(4) { u32(digest, it * 4) xor masks[it] }
    }

    private fun decrypt(block: ByteArray, key: LongArray): ByteArray {
        var a = u32(block, 0); var b = u32(block, 4)
        var c = u32(block, 8); var d = u32(block, 12)
        var sum = (DELTA * 16L) and MASK
        repeat(16) {
            d = (d - round(a, sum, key[2], key[1])) and MASK
            c = (c - round(d, sum, key[0], key[3])) and MASK
            b = (b - round(c, sum, key[2], key[3])) and MASK
            a = (a - round(b, sum, key[0], key[1])) and MASK
            sum = (sum - DELTA) and MASK
        }
        return ByteArray(16).also {
            put32(it, 0, a); put32(it, 4, b); put32(it, 8, c); put32(it, 12, d)
        }
    }

    fun read(file: File): PdzCatalog {
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            if (size < HEADER_SIZE + 8) error("Truncated PDZ")
            val head = ByteArray(HEADER_SIZE)
            raf.readFully(head)
            val offset = u32(head, 0x14)
            val catalogSize = u32(head, 0x18)
            if (catalogSize == 0L || catalogSize % RECORD_SIZE != 0L || catalogSize > 20_000_000L)
                error("Invalid catalog size")
            if (offset < HEADER_SIZE || offset + catalogSize > size - 8)
                error("Catalog outside file")
            raf.seek(offset + catalogSize)
            if (!ByteArray(4).also(raf::readFully).contentEquals("SSCT".toByteArray()))
                error("SSCT catalog trailer missing")
            raf.seek(size - 4)
            if (!ByteArray(4).also(raf::readFully).contentEquals("SSCE".toByteArray()))
                error("SSCE end marker missing")
            val key = words(head)
            raf.seek(offset)
            val cipher = ByteArray(catalogSize.toInt())
            raf.readFully(cipher)
            val decoded = cipher.clone()
            val full = (cipher.size / BLOCK_SIZE) * BLOCK_SIZE
            for (at in 0 until full step BLOCK_SIZE) {
                val block = decrypt(cipher.copyOfRange(at, at + BLOCK_SIZE), key)
                block.copyInto(decoded, at)
            }
            // Incomplete trailing blocks are plaintext in the verified PDZ catalog format.
            var expectedOffset = HEADER_SIZE.toLong()
            val entries = ArrayList<PdzEntry>(cipher.size / RECORD_SIZE)
            val used = HashSet<String>()
            for (i in decoded.indices step RECORD_SIZE) {
                val rawName = decoded.copyOfRange(i, i + 12)
                val nameLen = rawName.indexOf(0.toByte()).let { if (it < 0) 12 else it }
                val name = String(rawName, 0, nameLen, StandardCharsets.US_ASCII)
                if (!filename.matches(name) || rawName.take(nameLen).any { (it.toInt() and 0xff) > 127 })
                    error("Invalid catalog filename")
                val entryOffset = u32(decoded, i + 12)
                val entrySize = u32(decoded, i + 16)
                if (entrySize == 0L || entryOffset != expectedOffset || entryOffset + entrySize > offset)
                    error("Invalid catalog resource extent")
                if (!used.add(name)) error("Duplicate catalog resource")
                entries.add(PdzEntry(name, entryOffset, entrySize))
                expectedOffset = entryOffset + entrySize
            }
            if (expectedOffset != offset) error("Catalog does not cover resource section")
            return PdzCatalog(entries, size)
        }
    }

    /** Bounded, read-only resource extraction. Prevent untrusted metadata DoS. */
    fun resource(file: File, entry: PdzEntry, maxBytes: Int): ByteArray {
        if (entry.size < 1 || entry.size > maxBytes) error("Resource size outside safety limit")
        RandomAccessFile(file, "r").use { raf ->
            if (entry.offset < HEADER_SIZE || entry.offset + entry.size > raf.length()) error("Resource outside PDZ")
            val bytes = ByteArray(entry.size.toInt())
            raf.seek(entry.offset)
            raf.readFully(bytes)
            return bytes
        }
    }
}
