package org.openssreader.android

import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Display-only image processing. This file has no Android dependencies and is JVM-testable.
 *  Source PDZ, PDFs and stored scan pixels are NEVER changed. Call on a worker dispatcher.
 */
enum class ReaderEnhanceMode(val title: String, val explanation: String) {
    ORIGINAL("原始", "不改变扫描图像"),
    CLEAN("保真去灰底", "提亮纸张、减少灰底与泛黄，保留印刷细节"),
    GRAYSCALE("灰度文字增强", "增强黑白文字对比度，保留灰阶"),
    BLACK_WHITE("强黑白净化", "将文字页转换为黑字白底；插图页建议使用保真模式"),
    COLOR("彩色保真增强", "尽量保留插图及彩色印章，温和清理纸张底色")
}

/** OFF keeps the full scan; AUTO uses a separate detected crop on each page;
 *  REFERENCE reuses the four margins from one explicitly chosen book page.
 */
enum class ReaderCropMode { OFF, AUTO, REFERENCE }

/** Persisted edges are *cut distances*, not the content rectangle coordinates.
 *  They scale with each rendered page's dimensions, so 1550px continuous and
 *  2100px single/spread modes crop the same physical proportions.  On a page
 *  whose aspect ratio differs significantly from the reference (cover, foldout,
 *  rotated illustration) we fail OPEN rather than risk trimming real content.
 */
data class ReaderReferenceCrop(
    val pageIndex: Int,
    val pageWidth: Int,
    val pageHeight: Int,
    val leftCut: Int,
    val topCut: Int,
    val rightCut: Int,
    val bottomCut: Int
) {
    fun isValid(): Boolean =
        pageIndex >= 0 && pageWidth >= 160 && pageHeight >= 160 &&
        leftCut in 0..(pageWidth * 0.26f).roundToInt() &&
        rightCut in 0..(pageWidth * 0.26f).roundToInt() &&
        topCut in 0..(pageHeight * 0.28f).roundToInt() &&
        bottomCut in 0..(pageHeight * 0.28f).roundToInt() &&
        pageWidth - leftCut - rightCut >= pageWidth / 2 &&
        pageHeight - topCut - bottomCut >= pageHeight / 2 &&
        (leftCut + topCut + rightCut + bottomCut > 0)

    fun rectangleFor(width: Int, height: Int, mirrorHorizontal: Boolean = false): ReaderCropRect? {
        if (!isValid() || width < 160 || height < 160) return null
        val referenceRatio = pageWidth.toDouble() / pageHeight
        val currentRatio = width.toDouble() / height
        // Do not use a portrait reference crop on a landscape or foldout page.
        if (abs(currentRatio / referenceRatio - 1.0) > 0.12) return null
        val left = (leftCut.toDouble() * width / pageWidth).roundToInt().coerceIn(0, width - 1)
        val right = (rightCut.toDouble() * width / pageWidth).roundToInt().coerceIn(0, width - left - 1)
        val top = (topCut.toDouble() * height / pageHeight).roundToInt().coerceIn(0, height - 1)
        val bottom = (bottomCut.toDouble() * height / pageHeight).roundToInt().coerceIn(0, height - top - 1)
        val rect = if (mirrorHorizontal) ReaderCropRect(right, top, width - left, height - bottom)
            else ReaderCropRect(left, top, width - right, height - bottom)
        return if (rect.width >= width / 2 && rect.height >= height / 2) rect else null
    }
}

data class ReaderEnhanceSettings(
    val mode: ReaderEnhanceMode = ReaderEnhanceMode.ORIGINAL,
    val strength: Int = 50,
    val cropMode: ReaderCropMode = ReaderCropMode.OFF,
    val safeMargin: Int = 5,
    val referenceCrop: ReaderReferenceCrop? = null,
    /** Optional opposite-side page, chosen only when the reader wants greater precision. */
    val secondReferenceCrop: ReaderReferenceCrop? = null,
    /** Set by checking the page adjacent to the primary reference. */
    val recommendSecondReference: Boolean = false,
    /** Direct original / enhanced toggle, persisted separately from the selected filters. */
    val enabled: Boolean = false
) {
    fun normalized(): ReaderEnhanceSettings {
        val safeReference = referenceCrop?.takeIf { it.isValid() }
        val safeSecond = secondReferenceCrop?.takeIf {
            it.isValid() && safeReference != null &&
                ((it.pageIndex - safeReference.pageIndex) and 1) != 0
        }
        return copy(
            strength = strength.coerceIn(0, 100),
            safeMargin = safeMargin.coerceIn(2, 12),
            referenceCrop = safeReference,
            secondReferenceCrop = safeSecond,
            recommendSecondReference = recommendSecondReference && safeSecond == null && safeReference != null,
            cropMode = if (cropMode == ReaderCropMode.REFERENCE && safeReference == null)
                ReaderCropMode.OFF else cropMode
        )
    }
    fun requiresProcessing(): Boolean = mode != ReaderEnhanceMode.ORIGINAL || cropMode != ReaderCropMode.OFF
    fun displaySettings(): ReaderEnhanceSettings = if (enabled) this else copy(
        mode = ReaderEnhanceMode.ORIGINAL, cropMode = ReaderCropMode.OFF)

    /** A/B correspondence is based on actual zero-based PDF indices, not printed page numbers. */
    fun referenceForPage(pageIndex: Int, width: Int, height: Int): ReaderCropRect? {
        val first = referenceCrop ?: return null
        val opposite = ((pageIndex - first.pageIndex) and 1) != 0
        val secondary = if (opposite) secondReferenceCrop else null
        return if (secondary != null) secondary.rectangleFor(width, height)
            else first.rectangleFor(width, height, mirrorHorizontal = opposite)
    }
}


data class ReaderPixelPage(val width: Int, val height: Int, val pixels: IntArray)

data class ReaderCropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Lightweight tiled robust-background estimation, local contrast and Sauvola-style thresholding.
 * Uses a maximum of ~25x35 background cells instead of full-resolution integral-image buffers.
 * The incoming int array is read-only. Caller may supply a cancellation check per row.
 */
object ReaderImageEnhancer {
    private fun red(c: Int) = (c ushr 16) and 255
    private fun green(c: Int) = (c ushr 8) and 255
    private fun blue(c: Int) = c and 255
    private fun luma(c: Int): Int = (red(c) * 77 + green(c) * 150 + blue(c) * 29) ushr 8
    private fun toByte(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)

    /** Analyze exactly one explicitly selected reference page, never auto-pick a page.
     *  Returns null for sparse/unreliable pages or if nothing would be removed.
     *  This runs on a worker and does not modify the source's pixels.
     */
    fun detectReferenceCrop(
        source: ReaderPixelPage,
        pageIndex: Int,
        safeMargin: Int,
        checkCancelled: () -> Unit = {}
    ): ReaderReferenceCrop? {
        require(source.width > 0 && source.height > 0 && source.pixels.size == source.width * source.height)
        require(pageIndex >= 0)
        val grid = BackgroundGrid(source, checkCancelled)
        val rect = findCrop(source, grid, safeMargin.coerceIn(2, 12), checkCancelled)
        val reference = ReaderReferenceCrop(pageIndex, source.width, source.height,
            rect.left, rect.top, source.width - rect.right, source.height - rect.bottom)
        return reference.takeIf { it.isValid() }
    }

    /** Pure comparison: does a detected neighboring page differ materially from a
     *  horizontal MIRROR of the chosen reference? The 3% width threshold protects
     *  against scan noise without prompting for tiny variations.
     */
    fun materiallyDifferentOppositePage(
        primary: ReaderReferenceCrop,
        neighbor: ReaderReferenceCrop,
        threshold: Double = 0.03
    ): Boolean {
        if (!primary.isValid() || !neighbor.isValid()) return false
        if (((neighbor.pageIndex - primary.pageIndex) and 1) == 0) return false
        if (abs((neighbor.pageWidth.toDouble() / neighbor.pageHeight) /
                (primary.pageWidth.toDouble() / primary.pageHeight) - 1.0) > 0.12) return false
        val observedLeft = neighbor.leftCut.toDouble() / neighbor.pageWidth
        val observedRight = neighbor.rightCut.toDouble() / neighbor.pageWidth
        val expectedLeft = primary.rightCut.toDouble() / primary.pageWidth
        val expectedRight = primary.leftCut.toDouble() / primary.pageWidth
        return abs(observedLeft - expectedLeft) > threshold ||
            abs(observedRight - expectedRight) > threshold
    }

    fun process(
        source: ReaderPixelPage,
        rawSettings: ReaderEnhanceSettings,
        pageIndex: Int = 0,
        checkCancelled: () -> Unit = {}
    ): ReaderPixelPage {
        require(source.width > 0 && source.height > 0)
        require(source.pixels.size == source.width * source.height)
        val settings = rawSettings.normalized()
        if (!settings.requiresProcessing()) return source
        val grid = if (settings.mode != ReaderEnhanceMode.ORIGINAL || settings.cropMode == ReaderCropMode.AUTO)
            BackgroundGrid(source, checkCancelled) else null
        val full = ReaderCropRect(0, 0, source.width, source.height)
        val crop = when (settings.cropMode) {
            ReaderCropMode.OFF -> full
            ReaderCropMode.AUTO -> findCrop(source, requireNotNull(grid), settings.safeMargin, checkCancelled)
            ReaderCropMode.REFERENCE -> settings.referenceForPage(pageIndex, source.width, source.height) ?: full
        }
        val out = IntArray(crop.width * crop.height)
        val background = FloatArray(5)
        for (outY in 0 until crop.height) {
            if ((outY and 15) == 0) checkCancelled()
            val y = outY + crop.top
            val sy = y * source.width
            val dy = outY * crop.width
            for (outX in 0 until crop.width) {
                val x = outX + crop.left
                val src = source.pixels[sy + x]
                val result = if (settings.mode == ReaderEnhanceMode.ORIGINAL) src else {
                    requireNotNull(grid).sampleInto(x, y, background)
                    val br = background[0]; val bg = background[1]; val bb = background[2]
                    val bk = (br * 77f + bg * 150f + bb * 29f) / 256f
                    // Dark artwork or dark-mode scans are not paper; do not whitewash them.
                    if (bk < 120f) src else when (settings.mode) {
                        ReaderEnhanceMode.ORIGINAL -> src
                        ReaderEnhanceMode.CLEAN -> {
                            val contrast = 1.04f + settings.strength * 0.002f
                            pack(
                                255f - (br - red(src)) * 255f / max(br, 1f) * contrast,
                                255f - (bg - green(src)) * 255f / max(bg, 1f) * contrast,
                                255f - (bb - blue(src)) * 255f / max(bb, 1f) * contrast
                            )
                        }
                        ReaderEnhanceMode.GRAYSCALE -> {
                            val contrast = 1.08f + settings.strength * 0.003f
                            val normalized = 255f - (bk - luma(src)) * 255f / max(bk, 1f) * contrast
                            val v = toByte(normalized)
                            -0x1000000 or (v shl 16) or (v shl 8) or v
                        }
                        ReaderEnhanceMode.BLACK_WHITE -> {
                            // Sauvola local mean/std measured on sampled nearby source tiles.
                            // Stronger slider positions clean more gray ink/background.
                            val threshold = background[3] * (1f + (0.21f + settings.strength * 0.0013f) *
                                (background[4] / 128f - 1f))
                            val ink = luma(src) < threshold - ((settings.strength - 50) * 0.05f)
                            if (ink) -0x1000000 else -0x1
                        }
                        ReaderEnhanceMode.COLOR -> {
                            val weight = 0.30f + settings.strength * 0.006f
                            pack(
                                red(src) + (255f - (br - red(src)) * 255f / max(br, 1f) - red(src)) * weight,
                                green(src) + (255f - (bg - green(src)) * 255f / max(bg, 1f) - green(src)) * weight,
                                blue(src) + (255f - (bb - blue(src)) * 255f / max(bb, 1f) - blue(src)) * weight
                            )
                        }
                    }
                }
                out[dy + outX] = result
            }
        }
        return ReaderPixelPage(crop.width, crop.height, out)
    }

    private fun pack(r: Float, g: Float, b: Float): Int = -0x1000000 or
        (toByte(r) shl 16) or (toByte(g) shl 8) or toByte(b)

    /** Crop conservatively; preserve page numbers and marginalia, keep original for sparse/uncertain pages. */
    internal fun findCrop(
        source: ReaderPixelPage, grid: BackgroundGrid,
        safeMargin: Int, checkCancelled: () -> Unit = {}
    ): ReaderCropRect {
        val w = source.width
        val h = source.height
        val full = ReaderCropRect(0, 0, w, h)
        if (w < 160 || h < 160) return full
        val stride = max(2, max(w, h) / 750)
        val rowHits = IntArray((h + stride - 1) / stride)
        val colHits = IntArray((w + stride - 1) / stride)
        var total = 0
        for (y in 0 until h step stride) {
            if ((y / stride and 31) == 0) checkCancelled()
            for (x in 0 until w step stride) {
                val c = source.pixels[y * w + x]
                val paper = grid.sampleNearest(x, y)
                val background = (paper.r * 77f + paper.g * 150f + paper.b * 29f) / 256f
                val v = luma(c)
                // Ignore tiny differences from the page's background and printing paper texture.
                if (background >= 135f && v < 208 && (background - v) > 28f) {
                    rowHits[y / stride]++
                    colHits[x / stride]++
                    total++
                }
            }
        }
        val sampledPixels = rowHits.size * colHits.size
        // Blank, weak or illustration-dominated pages are not confidently crop-safe.
        if (total < max(24, sampledPixels / 3000)) return full
        val minRow = max(2, colHits.size / 220)
        val minCol = max(2, rowHits.size / 220)
        val rowTop = rowHits.indexOfFirst { it >= minRow }
        val rowBottom = rowHits.indexOfLast { it >= minRow }
        val colLeft = colHits.indexOfFirst { it >= minCol }
        val colRight = colHits.indexOfLast { it >= minCol }
        if (rowTop < 0 || colLeft < 0) return full
        val contentWidth = (colRight - colLeft + 1) * stride
        val contentHeight = (rowBottom - rowTop + 1) * stride
        if (contentWidth < w * 0.43 || contentHeight < h * 0.42) return full
        // Bound each edge's cut to avoid extreme zoom on unusual single-title pages.
        val padX = max(8, w * safeMargin / 100)
        val padY = max(8, h * safeMargin / 100)
        val maxCropX = (w * 0.26).toInt()
        val maxCropY = (h * 0.28).toInt()
        val left = (colLeft * stride - padX).coerceIn(0, maxCropX)
        val right = ((colRight + 1) * stride + padX).coerceIn(w - maxCropX, w)
        val top = (rowTop * stride - padY).coerceIn(0, maxCropY)
        val bottom = ((rowBottom + 1) * stride + padY).coerceIn(h - maxCropY, h)
        if (right - left < w / 2 || bottom - top < h / 2) return full
        return ReaderCropRect(left, top, right, bottom)
    }

    internal data class Background(val r: Float, val g: Float, val b: Float, val mean: Float, val std: Float)

    internal class BackgroundGrid(page: ReaderPixelPage, checkCancelled: () -> Unit = {}) {
        private val w = page.width
        private val h = page.height
        private val step = 96
        private val cols = (w + step - 1) / step
        private val rows = (h + step - 1) / step
        private val bg = Array(cols * rows) { Background(255f, 255f, 255f, 255f, 0f) }

        init {
            for (ty in 0 until rows) {
                checkCancelled()
                for (tx in 0 until cols) {
                    val counts = IntArray(64)
                    val x0 = tx * step
                    val y0 = ty * step
                    val x1 = min(w, x0 + step)
                    val y1 = min(h, y0 + step)
                    var count = 0
                    var sum = 0.0
                    var sum2 = 0.0
                    for (y in y0 until y1 step 3) {
                        for (x in x0 until x1 step 3) {
                            val v = luma(page.pixels[y * w + x])
                            counts[v ushr 2]++
                            sum += v
                            sum2 += v.toDouble() * v
                            count++
                        }
                    }
                    if (count == 0) continue
                    var selected = 0
                    var cutoff = 0
                    val required = max(1, (count * 0.25).toInt())
                    for (i in 63 downTo 0) {
                        selected += counts[i]
                        cutoff = i shl 2
                        if (selected >= required) break
                    }
                    var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
                    for (y in y0 until y1 step 3) for (x in x0 until x1 step 3) {
                        val c = page.pixels[y * w + x]
                        if (luma(c) >= cutoff) {
                            sr += red(c); sg += green(c); sb += blue(c); n++
                        }
                    }
                    val mu = (sum / count).toFloat()
                    val variance = max(0.0, sum2 / count - (sum / count) * (sum / count))
                    bg[ty * cols + tx] = Background((sr / max(n, 1)).toFloat(),
                        (sg / max(n, 1)).toFloat(), (sb / max(n, 1)).toFloat(),
                        mu, sqrt(variance).toFloat())
                }
            }
            // Smooth paper estimates across neighboring cells to avoid tile seams.
            val copy = bg.copyOf()
            for (ty in 0 until rows) for (tx in 0 until cols) {
                var r = 0f; var g = 0f; var b = 0f; var m = 0f; var s = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) {
                    val ix = tx + dx; val iy = ty + dy
                    if (ix in 0 until cols && iy in 0 until rows) {
                        val v = copy[iy * cols + ix]
                        r += v.r; g += v.g; b += v.b; m += v.mean; s += v.std; n++
                    }
                }
                bg[ty * cols + tx] = Background(r/n, g/n, b/n, m/n, s/n)
            }
        }

        fun sampleNearest(x: Int, y: Int): Background =
            bg[(y / step).coerceIn(0, rows - 1) * cols + (x / step).coerceIn(0, cols - 1)]

        fun sampleInto(x: Int, y: Int, target: FloatArray) {
            val gx = (x.toFloat() / step - 0.5f).coerceIn(0f, (cols - 1).toFloat())
            val gy = (y.toFloat() / step - 0.5f).coerceIn(0f, (rows - 1).toFloat())
            val x0 = gx.toInt(); val y0 = gy.toInt()
            val x1 = min(x0 + 1, cols - 1); val y1 = min(y0 + 1, rows - 1)
            val fx = gx - x0; val fy = gy - y0
            val a = bg[y0 * cols + x0]; val b = bg[y0 * cols + x1]
            val c = bg[y1 * cols + x0]; val d = bg[y1 * cols + x1]
            fun interpolate(v0: Float, v1: Float, v2: Float, v3: Float): Float =
                (v0 * (1f - fx) + v1 * fx) * (1f - fy) +
                (v2 * (1f - fx) + v3 * fx) * fy
            target[0] = interpolate(a.r,b.r,c.r,d.r)
            target[1] = interpolate(a.g,b.g,c.g,d.g)
            target[2] = interpolate(a.b,b.b,c.b,d.b)
            target[3] = interpolate(a.mean,b.mean,c.mean,d.mean)
            target[4] = interpolate(a.std,b.std,c.std,d.std)
        }
    }
}
