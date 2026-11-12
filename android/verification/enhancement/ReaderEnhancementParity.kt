import org.openssreader.android.ReaderEnhanceMode
import org.openssreader.android.ReaderCropMode
import org.openssreader.android.ReaderEnhanceSettings
import org.openssreader.android.ReaderReferenceCrop
import org.openssreader.android.ReaderImageEnhancer
import org.openssreader.android.ReaderPixelPage

private fun rgb(r: Int, g: Int, b: Int): Int = -0x1000000 or (r shl 16) or (g shl 8) or b
private fun gray(c: Int): Int = ((c ushr 16) and 255) * 77 / 256 + ((c ushr 8) and 255) * 150 / 256 + (c and 255) * 29 / 256
private fun checkMode() {
    val width = 320; val height = 420
    val src = IntArray(width * height) { y ->
        val x = y % width
        rgb(210 + (x / 100), 200 + (x / 100), 174 + (x / 100))
    }
    for (y in 55..365) for (x in 40..276) {
        if ((y % 17) in 0..2) src[y * width + x] = rgb(30, 27, 24)
    }
    val snapshot = src.clone()
    val input = ReaderPixelPage(width, height, src)
    check(ReaderImageEnhancer.process(input, ReaderEnhanceSettings()) === input)
    val clean = ReaderImageEnhancer.process(input, ReaderEnhanceSettings(mode = ReaderEnhanceMode.CLEAN))
    check(clean.width == width && clean.height == height)
    check(gray(clean.pixels[10 * width + 10]) > gray(src[10 * width + 10]) + 15) { "Gray/yellow paper was not whitened" }
    check(gray(clean.pixels[68 * width + 100]) < 80) { "Dark ink lost" }
    val grayscale = ReaderImageEnhancer.process(input, ReaderEnhanceSettings(mode = ReaderEnhanceMode.GRAYSCALE))
    val c = grayscale.pixels[30 * width + 30]
    check((c and 255) == ((c ushr 8) and 255) && (c and 255) == ((c ushr 16) and 255))
    val bw = ReaderImageEnhancer.process(input, ReaderEnhanceSettings(mode = ReaderEnhanceMode.BLACK_WHITE))
    check(bw.pixels.all { it == -0x1000000 || it == -0x1 }) { "BW mode not binary" }
    val color = ReaderImageEnhancer.process(input, ReaderEnhanceSettings(mode = ReaderEnhanceMode.COLOR))
    check(color.pixels.any { ((it ushr 16) and 255) != ((it ushr 8) and 255) })
    check(src.contentEquals(snapshot)) { "Input pixels mutated" }
    println("ENHANCE_MODES_AND_INPUT_IMMUTABILITY=PASS")
}
private fun checkCropping() {
    val w = 420; val h = 560
    val src = IntArray(w * h) { rgb(228, 228, 228) }
    for (y in 70 until 490) for (x in 63 until 355) {
        if (y % 15 < 4) src[y * w + x] = rgb(25, 25, 25)
    }
    // Fine page number and marginal number at the bottom should be preserved.
    for (y in 504 until 514) for (x in 193 until 216) src[y * w + x] = rgb(25,25,25)
    val crop = ReaderImageEnhancer.process(ReaderPixelPage(w, h, src), ReaderEnhanceSettings(cropMode = ReaderCropMode.AUTO))
    check(crop.width < w && crop.height < h) { "Expected to remove substantial blank margins" }
    check(crop.pixels.any { (it and 255) < 50 }) { "Ink lost" }
    // Snapshot is pure; no file mutations and conservative bound for illustration/sparse title.
    val sparse = IntArray(w * h) { rgb(228, 228, 228) }
    for (y in 260 until 270) for (x in 170 until 250) sparse[y * w + x] = rgb(20,20,20)
    val unchanged = ReaderImageEnhancer.process(ReaderPixelPage(w,h,sparse), ReaderEnhanceSettings(cropMode = ReaderCropMode.AUTO))
    check(unchanged.width == w && unchanged.height == h) { "Sparse title got dangerously cropped" }
    println("CONSERVATIVE_CROP_AND_SPARSE_FALLBACK=PASS")
}
private fun checkFixedReference() {
    val w = 420; val h = 560
    fun page(offset: Int = 0): ReaderPixelPage {
        val src = IntArray(w * h) { rgb(231, 229, 220) }
        for (y in 70 + offset until 490 + offset) for (x in 63 until 355)
            if (y % 15 < 4) src[y * w + x] = rgb(25, 25, 25)
        for (y in 504 until 514) for (x in 193 until 216) src[y * w + x] = rgb(25, 25, 25)
        return ReaderPixelPage(w, h, src)
    }
    val anchor = page()
    val fixed = ReaderImageEnhancer.detectReferenceCrop(anchor, pageIndex = 15, safeMargin = 5)
        ?: error("Expected to detect a usable reference page")
    check(fixed.pageIndex == 15)
    check(fixed.rectangleFor(w,h) != null)
    val exact = fixed.rectangleFor(w,h)!!
    val originalRect = ReaderImageEnhancer.findCrop(anchor, ReaderImageEnhancer.BackgroundGrid(anchor), 5)
    check(exact == originalRect) { "Reference margins must match exactly at source resolution" }
    val raw = ReaderEnhanceSettings(cropMode = ReaderCropMode.REFERENCE, referenceCrop = fixed)
    val result = ReaderImageEnhancer.process(page(12), raw)
    check(result.width == exact.width && result.height == exact.height) { "Crop must be fixed across pages, not page-wise" }
    check(result.pixels.any { (it and 255) < 50 })
    check(fixed.rectangleFor(840,1120) == ReaderCropRectScaled(originalRect, 2))
    check(fixed.rectangleFor(1120,840) == null) { "Landscape/foldout should fail open" }
    val sparse = ReaderPixelPage(w,h,IntArray(w*h){rgb(230,230,230)})
    check(ReaderImageEnhancer.process(sparse,raw).width == exact.width) { "Fixed mode should not auto-rescan sparse page" }
    check(ReaderImageEnhancer.process(sparse,raw.copy(cropMode=ReaderCropMode.OFF)) === sparse)
    check(ReaderImageEnhancer.process(sparse,raw.copy(cropMode=ReaderCropMode.AUTO)).width == w)
    check(ReaderImageEnhancer.detectReferenceCrop(sparse, 2, 5) == null) { "Sparse reference must be rejected" }
    val invalid = ReaderReferenceCrop(5,w,h,200,200,200,200)
    check(!invalid.isValid())
    check(raw.copy(referenceCrop=invalid).normalized().cropMode == ReaderCropMode.OFF)
    check(raw.referenceCrop == fixed)
    println("REFERENCE_CROP_FIXED_EDGES_SCALE_FALLBACK=PASS")
}
private fun ReaderCropRectScaled(rect: org.openssreader.android.ReaderCropRect, factor: Int) =
    org.openssreader.android.ReaderCropRect(rect.left*factor,rect.top*factor,rect.right*factor,rect.bottom*factor)
private fun checkMirroredAndDualReference() {
    val a = ReaderReferenceCrop(20, 500, 700, 25, 42, 85, 49)
    val b = ReaderReferenceCrop(21, 500, 700, 68, 44, 36, 51)
    val settings = ReaderEnhanceSettings(cropMode = ReaderCropMode.REFERENCE,
        referenceCrop = a, enabled = true)
    val same = settings.referenceForPage(22, 500, 700)!!
    val mirror = settings.referenceForPage(23, 500, 700)!!
    check(same.left == 25 && same.right == 415)
    check(mirror.left == 85 && mirror.right == 475) { "Opposite page must swap left/right cuts" }
    check(settings.referenceForPage(20, 1000, 1400) ==
        org.openssreader.android.ReaderCropRect(50,84,830,1302))
    check(settings.referenceForPage(21, 700, 500) == null) { "Landscape page must remain whole" }
    check(ReaderImageEnhancer.materiallyDifferentOppositePage(a,b)) { "Mismatch should prompt" }
    val bPerfectMirror = ReaderReferenceCrop(21,500,700,85,42,25,49)
    check(!ReaderImageEnhancer.materiallyDifferentOppositePage(a,bPerfectMirror))
    val tinyVariation = ReaderReferenceCrop(21,500,700,90,42,24,49)
    check(!ReaderImageEnhancer.materiallyDifferentOppositePage(a,tinyVariation))
    val dual = settings.copy(secondReferenceCrop=b, recommendSecondReference = true).normalized()
    check(dual.secondReferenceCrop == b && !dual.recommendSecondReference)
    check(dual.referenceForPage(21,500,700) == b.rectangleFor(500,700))
    check(dual.referenceForPage(23,500,700) == b.rectangleFor(500,700))
    check(dual.referenceForPage(22,500,700) == a.rectangleFor(500,700))
    check(settings.copy(secondReferenceCrop=a).normalized().secondReferenceCrop == null) {
        "Same side of book must not be accepted as second reference"
    }
    val raw = settings.copy(mode=ReaderEnhanceMode.BLACK_WHITE,enabled=false)
    check(raw.displaySettings().mode == ReaderEnhanceMode.ORIGINAL)
    check(raw.displaySettings().cropMode == ReaderCropMode.OFF)
    check(raw.mode == ReaderEnhanceMode.BLACK_WHITE && raw.referenceCrop == a) {
        "Temporarily showing original must retain the chosen enhancement settings"
    }
    val sample = ReaderPixelPage(500,700,IntArray(500*700) {rgb(238,238,238)})
    val croppedA = ReaderImageEnhancer.process(sample,settings,pageIndex=20)
    val croppedB = ReaderImageEnhancer.process(sample,settings,pageIndex=21)
    check(croppedA.width == croppedB.width && croppedA.height == croppedB.height)
    check(croppedA.width == 390 && croppedA.height == 609)
    println("MIRROR_DIFFERENCE_DETECTION_DUAL_REFERENCE_AND_PREVIEW=PASS")
}

fun main() {
    checkMode()
    checkCropping()
    checkFixedReference()
    checkMirroredAndDualReference()
    println("READER_ENHANCEMENT_E3_PARITY=PASS")
}
