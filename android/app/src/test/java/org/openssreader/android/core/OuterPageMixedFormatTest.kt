package org.openssreader.android.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Regression: encrypted JPEG and encrypted HH are legal in the SAME type2 PDZ. */
class OuterPageMixedFormatTest {
    private val bookKey = "TESTKEY8".toByteArray(Charsets.US_ASCII)
    private val mask = intArrayOf(0x00820208, 0x01220208, 0x00805038, 0x00920208)

    private fun reversedWords(source: ByteArray): ByteArray {
        val result = source.copyOf()
        for (start in result.indices step 4) {
            result[start] = source[start + 3]
            result[start + 1] = source[start + 2]
            result[start + 2] = source[start + 1]
            result[start + 3] = source[start]
        }
        return result
    }
    private fun encryptedPrefix(clear: ByteArray): ByteArray {
        val count = minOf(1024, clear.size) / 8 * 8
        require(count >= 112)
        val derived = MessageDigest.getInstance("MD5").digest(bookKey)
        mask.forEachIndexed { word, x ->
            for (i in 0..3) {
                val k = word * 4 + i
                derived[k] = (derived[k].toInt() xor (x ushr (8 * i))).toByte()
            }
        }
        val cipher = Cipher.getInstance("Blowfish/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "Blowfish"))
        val crypt = reversedWords(cipher.doFinal(reversedWords(clear.copyOfRange(0, count))))
        return crypt + clear.copyOfRange(count, clear.size)
    }
    @Test fun encryptedDirectJpegRestoresWithoutHHRequirement() {
        val jpeg = javaClass.classLoader!!.getResourceAsStream("synthetic_expected_jpeg_2.jpg")!!.readBytes()
        val encrypted = encryptedPrefix(jpeg)
        assertFalse(encrypted.copyOfRange(0, 2).contentEquals(byteArrayOf(0xff.toByte(), 0xd8.toByte())))
        val recovered = OuterPageRecovery.recover(encrypted, bookKey)
        assertArrayEquals(jpeg, recovered)
        assertEquals(0xff, recovered[0].toInt() and 0xff)
        assertEquals(0xd8, recovered[1].toInt() and 0xff)
        assertTrue(JpegPdfInput.inspect(recovered).width > 0)
    }
    @Test fun encryptedHHPageStillRestoresExactly() {
        val raw = ByteArray(1104) { i -> (i * 13).toByte() }
        raw[0] = 'H'.code.toByte(); raw[1] = 'H'.code.toByte()
        raw[2] = 2; raw[3] = 'J'.code.toByte()
        val recovered = OuterPageRecovery.recover(encryptedPrefix(raw), bookKey)
        assertArrayEquals(raw, recovered)
        assertEquals('H'.code.toByte(), recovered[0])
    }
    @Test fun unsupportedOuterDataStillFailsFormatValidation() {
        val clear = ByteArray(512) { (it * 17 + 11).toByte() }
        val result = OuterPageRecovery.recover(encryptedPrefix(clear), bookKey)
        assertArrayEquals(clear, result)
        assertFalse(Hh04JpegDecoder.accepts(result))
        assertFalse(BwPdgDecoder.accepts(result))
        assertFalse(Hh00EmbeddedImage.accepts(result))
    }
}
