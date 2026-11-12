package org.openssreader.android

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject

/** Private per-book display settings; never written to PDZ/PDF or bookshelf JSON.
 *  Backwards compatible with E1's `autoCrop` boolean in the same preferences.
 */
class ReaderEnhancementStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("reader-enhancement-v1", Context.MODE_PRIVATE)
    fun read(bookId: String): ReaderEnhanceSettings = runCatching {
        val data = prefs.getString("book:$bookId", null) ?: return ReaderEnhanceSettings()
        val json = JSONObject(data)
        val savedReference = json.optJSONObject("referenceCrop")?.let { obj ->
            ReaderReferenceCrop(
                pageIndex = obj.optInt("pageIndex", -1),
                pageWidth = obj.optInt("pageWidth", 0),
                pageHeight = obj.optInt("pageHeight", 0),
                leftCut = obj.optInt("leftCut", -1),
                topCut = obj.optInt("topCut", -1),
                rightCut = obj.optInt("rightCut", -1),
                bottomCut = obj.optInt("bottomCut", -1)
            )
        }
        val savedSecond = json.optJSONObject("secondReferenceCrop")?.let { obj ->
            ReaderReferenceCrop(
                pageIndex = obj.optInt("pageIndex", -1),
                pageWidth = obj.optInt("pageWidth", 0),
                pageHeight = obj.optInt("pageHeight", 0),
                leftCut = obj.optInt("leftCut", -1),
                topCut = obj.optInt("topCut", -1),
                rightCut = obj.optInt("rightCut", -1),
                bottomCut = obj.optInt("bottomCut", -1)
            )
        }
        val mode = ReaderCropMode.entries.firstOrNull { it.name == json.optString("cropMode") }
            ?: if (json.optBoolean("autoCrop", false)) ReaderCropMode.AUTO else ReaderCropMode.OFF
        val enhanceMode = ReaderEnhanceMode.entries.firstOrNull { it.name == json.optString("mode") }
            ?: ReaderEnhanceMode.ORIGINAL
        ReaderEnhanceSettings(
            mode = enhanceMode,
            strength = json.optInt("strength", 50),
            cropMode = mode,
            safeMargin = json.optInt("safeMargin", 5),
            referenceCrop = savedReference,
            secondReferenceCrop = savedSecond,
            recommendSecondReference = json.optBoolean("recommendSecondReference", false),
            // E1/E2 did not have an independent display toggle. Preserve their active mode.
            enabled = if (json.has("enabled")) json.optBoolean("enabled", false)
                else enhanceMode != ReaderEnhanceMode.ORIGINAL || mode != ReaderCropMode.OFF
        ).normalized()
    }.getOrDefault(ReaderEnhanceSettings())

    fun write(bookId: String, settings: ReaderEnhanceSettings) {
        val s = settings.normalized()
        val json = JSONObject().put("mode", s.mode.name).put("strength", s.strength)
            .put("cropMode", s.cropMode.name)
            .put("autoCrop", s.cropMode == ReaderCropMode.AUTO) // E1 downgrade-safe
            .put("safeMargin", s.safeMargin)
            .put("recommendSecondReference", s.recommendSecondReference)
            .put("enabled", s.enabled)
        s.referenceCrop?.let { r ->
            json.put("referenceCrop", JSONObject()
                .put("pageIndex", r.pageIndex)
                .put("pageWidth", r.pageWidth).put("pageHeight", r.pageHeight)
                .put("leftCut", r.leftCut).put("topCut", r.topCut)
                .put("rightCut", r.rightCut).put("bottomCut", r.bottomCut))
        }
        s.secondReferenceCrop?.let { r ->
            json.put("secondReferenceCrop", JSONObject()
                .put("pageIndex", r.pageIndex)
                .put("pageWidth", r.pageWidth).put("pageHeight", r.pageHeight)
                .put("leftCut", r.leftCut).put("topCut", r.topCut)
                .put("rightCut", r.rightCut).put("bottomCut", r.bottomCut))
        }
        prefs.edit().putString("book:$bookId", json.toString()).apply()
    }
}

/** Bitmaps are copies from PdfRenderer. Only a new in-memory Bitmap is enhanced.
 *  All work must be invoked from Dispatchers.Default rather than the UI thread.
 */
object ReaderBitmapEnhancer {
    private fun pixelsFrom(bitmap: Bitmap): ReaderPixelPage {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return ReaderPixelPage(bitmap.width, bitmap.height, pixels)
    }

    /** Reference detection is also display-only; it never writes an image to disk. */
    fun detectReferenceCrop(bitmap: Bitmap, pageIndex: Int, safeMargin: Int,
        checkCancelled: () -> Unit = {}): ReaderReferenceCrop? {
        val pixels = pixelsFrom(bitmap)
        checkCancelled()
        return ReaderImageEnhancer.detectReferenceCrop(pixels, pageIndex, safeMargin, checkCancelled)
    }

    fun process(bitmap: Bitmap, settings: ReaderEnhanceSettings, pageIndex: Int = 0,
        checkCancelled: () -> Unit = {}): Bitmap {
        if (!settings.normalized().requiresProcessing()) return bitmap
        val source = pixelsFrom(bitmap)
        checkCancelled()
        val output = ReaderImageEnhancer.process(source, settings, pageIndex, checkCancelled)
        checkCancelled()
        val enhanced = Bitmap.createBitmap(output.width, output.height, Bitmap.Config.ARGB_8888)
        enhanced.setPixels(output.pixels, 0, output.width, 0, 0, output.width, output.height)
        return enhanced
    }
}
