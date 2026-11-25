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

    @Test fun removedLocalOriginalDisappearsButCanBeDownloadedAgain() {
        val file = folder.newFile()
        val downloaded = book("one", file.absolutePath)
        val verified = setOf("one")
        assertEquals(listOf(downloaded), CentralCatalogue.visible(listOf(downloaded), false, verified))
        file.delete()
        val evicted = downloaded.copy(localPath = "")
        assertEquals(emptyList<BookEntity>(), CentralCatalogue.visible(listOf(evicted), false, verified))
        assertEquals(listOf(evicted), CentralCatalogue.visible(listOf(evicted), true, verified))
        val restored = evicted.copy(localPath = folder.newFile().absolutePath)
        assertEquals(listOf(restored), CentralCatalogue.visible(listOf(restored), false, verified))
    }

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
