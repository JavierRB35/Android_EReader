package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.backup.OriginalLinker
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OriginalLinkerTest {

    private fun tempWith(content: String): File =
        File.createTempFile("linker", ".epub").apply {
            writeText(content)
            deleteOnExit()
        }

    @Test
    fun `vincula por hash y copia a la caché local`() {
        val dir = createTempDirectory()
        val original = tempWith("contenido del libro")
        val linker = OriginalLinker(dir)
        val hash = requireNotNull(linker.sha256Hex(original))
        val waiting = listOf(book("b-1", hash))
        val linked = requireNotNull(linker.link(original, waiting))
        assertEquals("b-1", linked.id)
        assertTrue(linked.localPath.replace("\\", "/").contains("books/b-1"))
        assertTrue(File(linked.localPath).exists())
        assertEquals(hash, requireNotNull(linker.sha256Hex(File(linked.localPath))))
        dir.deleteRecursively()
    }

    @Test
    fun `no vincula cuando el hash no coincide`() {
        val dir = createTempDirectory()
        val original = tempWith("otro contenido")
        val linker = OriginalLinker(dir)
        val waiting = listOf(book("b-1", "hash-distinto"))
        assertNull(linker.link(original, waiting))
        dir.deleteRecursively()
    }

    @Test
    fun `detecta libros con archivo ausente`() {
        val linker = OriginalLinker(createTempDirectory())
        val missing = book("b-2", "hash").copy(localPath = "")
        val present = book("b-3", "hash2").copy(localPath = "/ruta/que/no/existe") // se cuenta como ausente si no existe
        val list = linker.missingFiles(listOf(missing, present))
        assertEquals(2, list.size)
    }

    @Test
    fun `archivo ilegible devuelve null sin lanzar`() {
        val dir = createTempDirectory()
        val linker = OriginalLinker(dir)
        assertNull(linker.sha256Hex(File(dir, "inexistente.epub")))
        dir.deleteRecursively()
    }

    private fun book(id: String, hash: String) = BookEntity(
        id = id, title = "T", authors = null, format = BookFormat.EPUB,
        fileName = "$id.epub", localPath = "", contentHash = hash,
        sizeBytes = 1, addedAt = 0,
    )

    private fun createTempDirectory(): File =
        File.createTempFile("dirs", "").let { file ->
            file.delete()
            file.mkdirs().let { file }
        }
}
