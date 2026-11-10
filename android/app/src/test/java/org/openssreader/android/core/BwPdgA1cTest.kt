package org.openssreader.android.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.io.RandomAccessFile

/** A1c local JVM test: deterministic synthetic data only; no real book keys. */
class BwPdgA1cTest {
    @Test fun outerPrefix02hAnd11hMatchLinuxPixels() {
        val pdz=javaClass.classLoader!!.getResourceAsStream("synthetic_bw_outer_a1c.pdz")!!.readBytes()
        val ref02=javaClass.classLoader!!.getResourceAsStream("synthetic_bw02_expected.bin")!!.readBytes()
        val ref11=javaClass.classLoader!!.getResourceAsStream("synthetic_bw11_expected.bin")!!.readBytes()
        val temp=Files.createTempFile("osr-a1c-test-", ".pdz").toFile()
        try {
            temp.writeBytes(pdz)
            val pageOrder=PdzContentsReader.layout(PdzCatalogReader.read(temp)).physicalPages!!
            assertEquals(3,pageOrder.size)
            RandomAccessFile(temp,"r").use { input ->
                for ((i,reference) in listOf(ref02,ref11).withIndex()) {
                    val record=pageOrder[i];val data=ByteArray(record.size.toInt())
                    input.seek(record.offset);input.readFully(data)
                    val decoded=BwPdgDecoder.decode(OuterPageRecovery.recover(data,"TESTKEY8".toByteArray()))
                    assertEquals(64,decoded.width);assertEquals(12,decoded.height)
                    assertArrayEquals(reference,decoded.packedWhiteOne)
                    assertEquals(if(i==0)"02H" else "11H",decoded.format)
                }
            }
        } finally {temp.delete()}
    }
    @Test fun invalidOrWrongAuthorizationContextFailsClosed() {
        // Outer recovery is format-neutral (JPEG pages are valid too). A wrong
        // key must fail in the downstream strict page format decoder.
        val garbage = OuterPageRecovery.recover(ByteArray(112),"BADKEY!!".toByteArray())
        assertFalse(Hh04JpegDecoder.accepts(garbage))
        assertFalse(BwPdgDecoder.accepts(garbage))
        assertFalse(garbage.size > 2 && (garbage[0].toInt() and 255) == 255 &&
            (garbage[1].toInt() and 255) == 216)
        assertThrows(OuterPageRecovery.Invalid::class.java) {
            OuterPageRecovery.recover(ByteArray(112),byteArrayOf(1))
        }
    }
}
