package org.openssreader.android

import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.openssreader.android.core.BookMetadataReader
import org.openssreader.android.core.PdzCatalogReader
import org.openssreader.android.core.SafeXml
import java.io.File

/** Run by connectedAndroidTest: exercises Android's *real* XML parser and MediaStore. */
@RunWith(AndroidJUnit4::class)
class ImportAndStorageAcceptanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun metadataXmlImportsOnAndroidParser() {
        val file = File(context.cacheDir, "android-import-xml-fixture.pdz")
        try {
            context.assets.open("synthetic_direct_jpeg.pdz").use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            val info = BookMetadataReader.read(file, PdzCatalogReader.read(file))
            assertEquals("合成测试：荷兰史", info?.title)
            assertTrue((info?.contributors?.size ?: 0) >= 1)
            var blocked = false
            try { SafeXml.documentText("<!DOCTYPE a [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><a>&x;</a>") }
            catch (_: IllegalArgumentException) { blocked = true }
            assertTrue("DTD/XXE must be rejected", blocked)
        } finally { file.delete() }
    }

    @Test fun defaultVisibleFoldersCreatedUsingMediaStore() {
        if (Build.VERSION.SDK_INT < 29) return
        val storage = PublicBookStorage(context)
        // Never overwrite a tester's chosen SAF folder setting.
        storage.ensureDefaultDirectories()
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        for (kind in PublicBookStorage.Kind.values().filterNot(storage::isCustom)) {
            val rel = "Download/OpenSSReader/${kind.subdir}/"
            val found = context.contentResolver.query(collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(rel, "OpenSSReader-文件夹说明.txt"), null)
                ?.use { it.moveToFirst() } == true
            assertTrue("Visible folder/marker missing: $rel", found)
        }
    }
}
