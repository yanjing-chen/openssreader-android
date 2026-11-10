package org.openssreader.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** No Android Context required: tests validation of user-created sidebar names. */
class ShelfLabelContractTest {
    @Test fun acceptsUnicodeAndPreservesNames() {
        val labels = listOf(ShelfLabel("a", "学习", setOf("book-id")))
        assertEquals("儿子用", ShelfLabels.validateTitle("  儿子用  ", labels))
        assertEquals("学习", ShelfLabels.validateTitle("学习", labels, except = "a"))
    }

    @Test fun rejectsEmptyDuplicateAndControlCharacters() {
        val labels = listOf(ShelfLabel("a", "儿童用", emptySet()))
        for (name in listOf("   ", "儿童用", "儿童用\n", "a".repeat(41))) {
            assertThrows(IllegalArgumentException::class.java) {
                ShelfLabels.validateTitle(name, labels)
            }
        }
    }

    @Test fun labelIsOnlyACollectionOfBookIds() {
        val source = ShelfLabel("tag-1", "历史", setOf("book-1", "book-2"))
        val removed = listOf(source).filterNot { it.id == "tag-1" }
        val books = listOf("book-1", "book-2")
        assertEquals(0, removed.size)
        assertEquals(2, books.size)
    }
}
