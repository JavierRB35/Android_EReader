package com.javierrb35.bibliotecaia.backup

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.CategoryEntity
import com.javierrb35.bibliotecaia.data.TagEntity
import org.junit.Assert.*
import org.junit.Test

class RestoreIdentityPlannerTest {
    private fun book(id: String, hash: String) = BookEntity(
        id = id, title = "Quijote", authors = null, format = BookFormat.EPUB,
        fileName = "quijote.epub", localPath = "", contentHash = hash,
        sizeBytes = 32, addedAt = 7,
    )
    private fun backup(books: List<BookEntity> = emptyList(),
                       categories: List<CategoryEntity> = emptyList(),
                       tags: List<TagEntity> = emptyList()) = LibrarySnapshot(
        books = books, categories = categories, tags = tags,
        collections = emptyList(), bookCategories = emptyList(),
        bookTags = emptyList(), collectionBooks = emptyList(),
        annotations = emptyList(), versions = emptyList(), positions = emptyList(),
    )

    @Test fun remapsSameShaToExistingLocalBookWithoutReplacingItsPath() {
        val source = book("from-drive", "hash-quijote")
        val local = book("already-imported", "hash-quijote")
            .copy(localPath = "/data/user/books/actual.epub")
        val map = RestoreIdentityPlanner.plan(backup(books = listOf(source)),
            listOf(local), emptyList(), emptyList())
        assertEquals("already-imported", map.book("from-drive"))
        assertEquals("/data/user/books/actual.epub", local.localPath)
    }

    @Test fun remapsRootShelvesAndTagsByName() {
        val source = backup(
            categories = listOf(CategoryEntity("remote-cat", "Novela")),
            tags = listOf(TagEntity("remote-tag", "CLÁSICOS")),
        )
        val mapping = RestoreIdentityPlanner.plan(source, emptyList(),
            listOf(CategoryEntity("local-cat", "Novela")),
            listOf(TagEntity("local-tag", "Clásicos")))
        assertEquals("local-cat", mapping.category("remote-cat"))
        assertEquals("local-tag", mapping.tag("remote-tag"))
    }

    @Test fun sameNameInDifferentChildCategoryIsNotCollapsed() {
        val incoming = backup(categories = listOf(CategoryEntity("child-new", "Otros", "parent-new")))
        val existing = listOf(CategoryEntity("child-old", "Otros", "parent-old"))
        val mapping = RestoreIdentityPlanner.plan(incoming, emptyList(), existing, emptyList())
        assertEquals("child-new", mapping.category("child-new"))
    }

    @Test fun rejectsSameUuidAssignedToDifferentBookEditions() {
        val incoming = backup(books = listOf(book("shared-id", "hash-1")))
        try {
            RestoreIdentityPlanner.plan(incoming, listOf(book("shared-id", "hash-2")),
                emptyList(), emptyList())
            fail("An ID collision must not silently change annotation targets")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("otra edición"))
        }
    }

    @Test fun rejectsDuplicateHashInDamagedBackup() {
        val incoming = backup(books = listOf(book("a", "hash-1"), book("b", "hash-1")))
        try {
            RestoreIdentityPlanner.plan(incoming, emptyList(), emptyList(), emptyList())
            fail("Cannot restore duplicate hashes with different UUIDs")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("duplicado"))
        }
    }
}
