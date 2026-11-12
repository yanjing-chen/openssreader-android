package org.openssreader.android

import org.junit.Assert.*
import org.junit.Test

class ReaderImageEnhancerTest {
    private fun rgb(r: Int, g: Int, b: Int) = -0x1000000 or (r shl 16) or (g shl 8) or b
    private fun lum(c: Int) = (((c ushr 16) and 255) * 77 + ((c ushr 8) and 255) * 150 +
        (c and 255) * 29) ushr 8

    @Test fun originalIsUnchangedAndModeWhitePaperKeepsInk() {
        val w = 360; val h = 480
        val raw = IntArray(w*h) { rgb(204, 199, 175) }
        for (y in 90 until 360) for (x in 60 until 300) if (y % 19 < 4)
            raw[y * w + x] = rgb(36, 31, 26)
        val baseline = raw.clone()
        val page = ReaderPixelPage(w,h,raw)
        assertSame(page, ReaderImageEnhancer.process(page, ReaderEnhanceSettings()))
        val result = ReaderImageEnhancer.process(page, ReaderEnhanceSettings(ReaderEnhanceMode.CLEAN))
        assertTrue(lum(result.pixels[12*w+12]) > lum(raw[12*w+12]) + 18)
        assertTrue(lum(result.pixels[95*w+95]) < 90)
        assertArrayEquals(baseline, raw)
    }

    @Test fun grayscaleAndBinaryAreWellFormed() {
        val w = 192; val h = 256
        val raw = IntArray(w*h) { i -> if (i % 31 < 4) rgb(55,55,55) else rgb(213,204,195) }
        val page = ReaderPixelPage(w,h,raw)
        val gray = ReaderImageEnhancer.process(page, ReaderEnhanceSettings(ReaderEnhanceMode.GRAYSCALE))
        assertTrue(gray.pixels.all { ((it ushr 16) and 255) == ((it ushr 8) and 255) &&
            ((it ushr 8) and 255) == (it and 255) })
        val bw = ReaderImageEnhancer.process(page, ReaderEnhanceSettings(ReaderEnhanceMode.BLACK_WHITE))
        assertTrue(bw.pixels.all { it == -1 || it == -0x1000000 })
    }

    @Test fun cropKeepsSparseFootnoteAndFailsOpenWhenUncertain() {
        val w = 480; val h = 640
        val raw = IntArray(w*h) { rgb(235,235,235) }
        for (y in 100 until 540) for (x in 70 until 408) if (y % 18 < 4)
            raw[y*w+x] = rgb(24,24,24)
        for (y in 557 until 567) for (x in 204 until 230) raw[y*w+x] = rgb(24,24,24)
        val page = ReaderPixelPage(w,h,raw)
        val rect = ReaderImageEnhancer.findCrop(page, ReaderImageEnhancer.BackgroundGrid(page),5)
        assertTrue(rect.width < w && rect.height < h)
        assertTrue(217 in rect.left until rect.right && 562 in rect.top until rect.bottom)
        val result = ReaderImageEnhancer.process(page, ReaderEnhanceSettings(cropMode=ReaderCropMode.AUTO))
        assertEquals(rgb(24,24,24),result.pixels[(562-rect.top)*rect.width+(217-rect.left)])
        val sparse = IntArray(w*h) { rgb(235,235,235) }
        for (y in 300 until 310) for (x in 205 until 275) sparse[y*w+x] = rgb(24,24,24)
        val sparseResult = ReaderImageEnhancer.process(ReaderPixelPage(w,h,sparse),ReaderEnhanceSettings(cropMode=ReaderCropMode.AUTO))
        assertEquals(w,sparseResult.width)
        assertEquals(h,sparseResult.height)
    }
    @Test fun fixedReferenceCropRetainsDimensionsAcrossBookAndScalesForContinuous() {
        val w=480; val h=640
        val raw = IntArray(w*h) { rgb(235,235,235) }
        for (y in 95 until 535) for (x in 70 until 410)
            if (y % 18 < 4) raw[y*w+x] = rgb(24,24,24)
        val page = ReaderPixelPage(w,h,raw)
        val reference = ReaderImageEnhancer.detectReferenceCrop(page, 42, 5)!!
        assertEquals(42,reference.pageIndex)
        val rect = reference.rectangleFor(w,h)!!
        assertTrue(rect.width < w)
        val settings = ReaderEnhanceSettings(cropMode=ReaderCropMode.REFERENCE,referenceCrop=reference)
        val page2 = ReaderPixelPage(w,h,IntArray(w*h) { rgb(235,235,235) })
        val result = ReaderImageEnhancer.process(page2,settings)
        assertEquals(rect.width,result.width)
        assertEquals(rect.height,result.height)
        val rectScaled = reference.rectangleFor(w*2,h*2)!!
        assertEquals(rect.width*2,rectScaled.width)
        assertEquals(rect.height*2,rectScaled.height)
        assertNull(reference.rectangleFor(h,w))
        assertEquals(w,ReaderImageEnhancer.process(page2,settings.copy(cropMode=ReaderCropMode.OFF)).width)
        assertEquals(w,ReaderImageEnhancer.process(page2,settings.copy(cropMode=ReaderCropMode.AUTO)).width)
    }

    @Test fun invalidReferenceIsRejectedAndDoesNotCrop() {
        val invalid = ReaderReferenceCrop(2,480,640,300,0,300,0)
        assertFalse(invalid.isValid())
        val settings = ReaderEnhanceSettings(cropMode=ReaderCropMode.REFERENCE,referenceCrop=invalid)
        assertEquals(ReaderCropMode.OFF,settings.normalized().cropMode)
        val blank = ReaderPixelPage(200,250,IntArray(200*250){rgb(235,235,235)})
        assertSame(blank,ReaderImageEnhancer.process(blank,settings))
        assertNull(ReaderImageEnhancer.detectReferenceCrop(blank,0,5))
    }

}
