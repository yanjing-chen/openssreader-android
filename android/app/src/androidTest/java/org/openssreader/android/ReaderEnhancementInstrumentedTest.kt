package org.openssreader.android

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side Android Bitmap + settings tests; not claimed to have run on the server. */
@RunWith(AndroidJUnit4::class)
class ReaderEnhancementInstrumentedTest {
    @Test fun actualBitmapEnhancesWithoutAlteringTheSource() {
        val image = Bitmap.createBitmap(320, 440, Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.rgb(205,205,205))
        for (y in 80 until 390) for (x in 45 until 275) if (y % 16 < 3)
            image.setPixel(x,y,android.graphics.Color.rgb(22,22,22))
        val colorBefore = image.getPixel(5,5)
        val clean = ReaderBitmapEnhancer.process(image, ReaderEnhanceSettings(ReaderEnhanceMode.CLEAN))
        assertTrue(android.graphics.Color.red(clean.getPixel(5,5)) > 230)
        assertEquals(colorBefore,image.getPixel(5,5))
        val crop = ReaderBitmapEnhancer.process(image, ReaderEnhanceSettings(cropMode=ReaderCropMode.AUTO))
        assertTrue(crop.width <= image.width && crop.height <= image.height)
    }

    @Test fun perBookSettingsArePrivateAndPersistent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReaderEnhancementStore(context)
        val a = "enhancement-device-test-a"
        val b = "enhancement-device-test-b"
        val prefs = context.getSharedPreferences("reader-enhancement-v1", 0)
        try {
            store.write(a, ReaderEnhanceSettings(ReaderEnhanceMode.BLACK_WHITE, 83, ReaderCropMode.AUTO, 7))
            assertEquals(ReaderEnhanceMode.BLACK_WHITE, store.read(a).mode)
            assertEquals(83, store.read(a).strength)
            assertEquals(ReaderCropMode.AUTO, store.read(a).cropMode)
            assertEquals(7, store.read(a).safeMargin)
            assertEquals(ReaderEnhanceMode.ORIGINAL, store.read(b).mode)
        } finally {
            prefs.edit().remove("book:$a").remove("book:$b").commit()
        }
    }
    @Test fun referenceSelectionPersistsPerBookAndE1AutoSettingMigrates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReaderEnhancementStore(context)
        val prefs = context.getSharedPreferences("reader-enhancement-v1", 0)
        val fixed = ReaderReferenceCrop(19, 480, 640, 60, 45, 62, 58)
        try {
            store.write("e2-fixed", ReaderEnhanceSettings(cropMode=ReaderCropMode.REFERENCE,referenceCrop=fixed))
            val restored = store.read("e2-fixed")
            assertEquals(ReaderCropMode.REFERENCE,restored.cropMode)
            assertEquals(fixed,restored.referenceCrop)
            store.write("e2-fixed", restored.copy(cropMode=ReaderCropMode.OFF))
            assertEquals(fixed,store.read("e2-fixed").referenceCrop)
            prefs.edit().putString("book:e1-legacy", "{\"mode\":\"CLEAN\",\"autoCrop\":true,\"safeMargin\":7}").commit()
            assertEquals(ReaderCropMode.AUTO,store.read("e1-legacy").cropMode)
        } finally {
            prefs.edit().remove("book:e2-fixed").remove("book:e1-legacy").commit()
        }
    }

    @Test fun e3MirroringSecondReferenceAndDisplayTogglePersist() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ReaderEnhancementStore(context)
        val prefs = context.getSharedPreferences("reader-enhancement-v1", 0)
        val id = "e3-reference-test"
        val first = ReaderReferenceCrop(20, 500, 700, 25, 42, 85, 49)
        val other = ReaderReferenceCrop(21, 500, 700, 68, 44, 36, 51)
        try {
            store.write(id, ReaderEnhanceSettings(mode=ReaderEnhanceMode.CLEAN,
                cropMode=ReaderCropMode.REFERENCE, referenceCrop=first,
                secondReferenceCrop=other, enabled=false))
            val disabled = store.read(id)
            assertFalse(disabled.enabled)
            assertEquals(first, disabled.referenceCrop)
            assertEquals(other, disabled.secondReferenceCrop)
            assertEquals(ReaderEnhanceMode.CLEAN, disabled.mode)
            assertEquals(ReaderEnhanceMode.ORIGINAL, disabled.displaySettings().mode)
            store.write(id,disabled.copy(enabled=true))
            assertTrue(store.read(id).enabled)
            assertEquals(other.rectangleFor(500,700), store.read(id).referenceForPage(23,500,700))
        } finally {
            prefs.edit().remove("book:$id").commit()
        }
    }

}
