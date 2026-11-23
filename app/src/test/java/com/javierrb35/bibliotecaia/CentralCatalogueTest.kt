package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.backup.CentralCatalogue
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CentralCatalogueTest {
    @get:Rule val folder = TemporaryFolder()
    private fun book(id: String, path: String = "") = BookEntity(id, id, null,
        BookFormat.EPUB, "${id}.epub", path, id, 1L, 1L)

    @Test fun staleMetadataNeverAppearsAsDownloadableOrLocal() {
        val local = book("local", folder.newFile().absolutePath)
        val remote = book("remote")
        val orphan = book("orphan")
        val all = listOf(local, remote, orphan)
        val hashes = setOf("remote")
        assertEquals(listOf(local), CentralCatalogue.visible(all, false, hashes))
        assertEquals(listOf(remote), CentralCatalogue.visible(all, true, hashes))
        assertEquals(listOf(orphan), CentralCatalogue.missing(all, hashes))
        assertEquals(emptyList<BookEntity>(), CentralCatalogue.visible(all, true, null))
    }
}
