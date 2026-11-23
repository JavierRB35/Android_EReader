package com.javierrb35.bibliotecaia.importer

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalBookCopyRemoverTest {
    @get:Rule val temp = TemporaryFolder()

    private fun book(id: String, path: String) = BookEntity(
        id = id,
        title = "Libro de prueba",
        authors = "Autor",
        format = BookFormat.EPUB,
        fileName = "original.epub",
        localPath = path,
        contentHash = "test-hash",
        sizeBytes = 5,
        addedAt = 123
    )

    @Test fun removesOnlyOwnPrivateCopy() {
        val root = temp.newFolder("app")
        val own = File(root, "books/book-1/original.epub").apply {
            parentFile!!.mkdirs()
            writeText("book1")
        }
        val other = File(root, "books/book-2/original.epub").apply {
            parentFile!!.mkdirs()
            writeText("book2")
        }

        LocalBookCopyRemover.delete(root, book("book-1", own.absolutePath))

        assertFalse(own.exists())
        assertTrue("Other book must survive", other.isFile)
    }

    @Test fun forbidsDeletingFileOutsideAppBookDirectory() {
        val root = temp.newFolder("app")
        val outside = temp.newFile("drive-mock.epub").apply { writeText("do not delete") }

        try {
            LocalBookCopyRemover.delete(root, book("book-1", outside.absolutePath))
            fail("Must reject a document not owned by the book's private directory")
        } catch (_: IllegalArgumentException) {
            assertTrue("Outside original must survive", outside.isFile)
        }
    }

    @Test fun forbidsDeletingAnotherBooksPrivateCopy() {
        val root = temp.newFolder("app")
        val other = File(root, "books/book-2/original.epub").apply {
            parentFile!!.mkdirs()
            writeText("book2")
        }
        try {
            LocalBookCopyRemover.delete(root, book("book-1", other.absolutePath))
            fail("Must not delete another book's file")
        } catch (_: IllegalArgumentException) {
            assertTrue(other.isFile)
        }
    }
}
