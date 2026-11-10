package org.openssreader.android.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files

/** Pure JVM tests; no Android UI/SDK types. */
class Hh04CoreTest {
    private fun fixture(name: String) = File(requireNotNull(javaClass.classLoader?.getResource(name)).toURI())

    @Test fun inner04hIsByteExactWithPythonReference() {
        val pdz = fixture("synthetic_hh04_mixed.pdz")
        val catalog = PdzCatalogReader.read(pdz)
        val pages = requireNotNull(PdzContentsReader.layout(catalog).physicalPages)
        assertEquals(3, pages.size)
        val last = pages.last()
        val clear = RandomAccessFile(pdz, "r").use { f ->
            f.seek(last.offset)
            ByteArray(last.size.toInt()).also { f.readFully(it) }
        }
        val jpeg = Hh04JpegDecoder.decode(clear)
        assertArrayEquals(fixture("synthetic_expected_jpeg_2.jpg").readBytes(), jpeg.data)
        assertEquals(78, jpeg.width)
        assertEquals(67, jpeg.height)
        assertEquals(3, JpegPdfInput.inspect(jpeg.data, jpeg.width, jpeg.height).channels)
    }

    @Test fun rejectsOuterEncryptedOrIncorrect04hHeader() {
        assertFalse(Hh04JpegDecoder.accepts(ByteArray(140)))
        assertThrows(Hh04JpegDecoder.Unsupported::class.java) {
            Hh04JpegDecoder.decode(ByteArray(140))
        }
    }

    @Test fun jpegIsEmbeddedWithoutLossyResampling() {
        val jpg = fixture("synthetic_expected_jpeg_2.jpg").readBytes()
        val target = Files.createTempFile("osr-04h-", ".pdf").toFile()
        try {
            FileOutputStream(target).use { out ->
                LosslessJpegPdf(out).apply {
                    addPage(jpg, JpegPdfInput.inspect(jpg))
                    finish()
                }
            }
            assertTrue(target.readBytes().indexOfSubarray(jpg) > 0)
            assertTrue(target.length() > jpg.size)
        } finally { target.delete() }
    }
    private fun ByteArray.indexOfSubarray(fragment: ByteArray): Int {
        for (at in 0..(size-fragment.size)) {
            var ok = true
            for (i in fragment.indices) if (this[at+i] != fragment[i]) { ok = false; break }
            if (ok) return at
        }
        return -1
    }
}
