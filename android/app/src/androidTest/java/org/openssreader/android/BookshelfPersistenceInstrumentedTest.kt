package org.openssreader.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android org.json + real file I/O; isolated under app cache, never the user's library. */
@RunWith(AndroidJUnit4::class)
class BookshelfPersistenceInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun withStorage(test: (PublicBookStorage, File) -> Unit) {
        val root = File(context.cacheDir, "osr-bookshelf-test-${UUID.randomUUID()}")
        try {
            val storage = PublicBookStorage(context, isolatedTestRoot = root)
            storage.ensureDefaultDirectories()
            test(storage, root)
        } finally { root.deleteRecursively() }
    }
    private fun book(id: String = "11111111-2222-3333-4444-555555555555") = ShelfBook(
        id = id, filename = "荷兰史_作者_出版社_2024_12345678.pdz", type = "PDZ", title = "荷兰史",
        ssid = "12345678", publicPdzName = "荷兰史_作者_出版社_2024_12345678.pdz")
    private fun state(vararg books: ShelfBook) = BookshelfIndex.encode(
        BookshelfIndex.Snapshot(books.toList(), emptySet(), emptyList()))

    @Test fun downloadedBookGetsBookNamedJsonAndSurvivesNewStorageInstance() = withStorage { storage, root ->
        val b = book()
        File(root, "pdz/${b.publicPdzName}").writeBytes(byteArrayOf(0x50, 0x44, 0x5a))
        storage.writeShelfIndex(state(b))
        val json = File(root, "bookshelf/荷兰史_作者_出版社_2024_12345678.json")
        assertTrue("The JSON filename must match the PDZ stem", json.isFile)
        assertFalse(File(root, "bookshelf/${b.id}.json").exists())
        val reloaded = PublicBookStorage(context, isolatedTestRoot = root)
        val restored = BookshelfIndex.decode(requireNotNull(reloaded.readShelfIndex())).books.single()
        assertEquals(b.id, restored.id)
        assertEquals(b.publicPdzName, restored.publicPdzName)
    }

    @Test fun finishedPdfIsWrittenIntoSameJsonNotSecondRecord() = withStorage { storage, root ->
        val original = book()
        storage.writeShelfIndex(state(original))
        val converted = original.copy(publicPdfName = "荷兰史_作者_出版社_2024_12345678.pdf", totalPages = 470)
        storage.writeShelfIndex(state(converted))
        val names = File(root, "bookshelf").listFiles()!!.map { it.name }
        assertEquals(1, names.count { it.endsWith(".json") && it != "labels.json" })
        val restored = BookshelfIndex.decode(requireNotNull(storage.readShelfIndex())).books.single()
        assertEquals(converted.publicPdfName, restored.publicPdfName)
        assertEquals(470, restored.totalPages)
    }

    @Test fun oldUuidJsonIsMigratedWithoutLosingFavoriteOrTags() = withStorage { storage, root ->
        val original = book()
        val record = BookshelfIndex.encodeRecord(original, true, setOf("tag1"))
        File(root, "bookshelf/${original.id}.json").writeText(record)
        File(root, "bookshelf/labels.json").writeText(BookshelfIndex.encodeLabelDefinitions(
            listOf(ShelfLabel("tag1", "收藏", setOf(original.id)))))
        storage.migrateLegacyRecordNamesIfPresent()
        assertFalse(File(root, "bookshelf/${original.id}.json").exists())
        assertTrue(File(root, "bookshelf/荷兰史_作者_出版社_2024_12345678.json").exists())
        val restored = BookshelfIndex.decode(requireNotNull(storage.readShelfIndex()))
        assertTrue(original.id in restored.favorites)
        assertTrue(original.id in restored.labels.single().bookIds)
    }

    @Test fun sameStemNeverOverwritesAnotherBookRecord() = withStorage { storage, root ->
        val original = book()
        storage.writeShelfIndex(state(original))
        val different = book("99999999-2222-3333-4444-555555555555")
        val attempt = runCatching { storage.writeShelfIndex(state(different)) }
        assertTrue("Must reject JSON name collision", attempt.isFailure)
        val restored = BookshelfIndex.decode(requireNotNull(storage.readShelfIndex())).books.single()
        assertEquals(original.id, restored.id)
        assertTrue(File(root, "bookshelf/荷兰史_作者_出版社_2024_12345678.json").exists())
    }

    @Test fun explicitRemovalDeletesOnlyJsonNotOriginal() = withStorage { storage, root ->
        val original = book()
        val source = File(root, "pdz/${original.publicPdzName}").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        storage.writeShelfIndex(state(original))
        storage.writeShelfIndex(state())
        assertFalse(File(root, "bookshelf/荷兰史_作者_出版社_2024_12345678.json").exists())
        assertTrue(source.isFile)
        assertTrue(BookshelfIndex.decode(requireNotNull(storage.readShelfIndex())).books.isEmpty())
    }
}
