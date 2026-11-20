package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.backup.BackupCodec
import com.javierrb35.bibliotecaia.backup.LibrarySnapshot
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.CategoryEntity
import com.javierrb35.bibliotecaia.data.ReadingPositionEntity
import com.javierrb35.bibliotecaia.data.ReadingStatus
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private fun sample(): LibrarySnapshot = LibrarySnapshot(
        books = listOf(
            BookEntity(
                id = "book-1", title = "Don Quijote de la Mancha", authors = "Miguel de Cervantes",
                format = BookFormat.EPUB, fileName = "don-quijote.epub", localPath = "/device/only/path.epub",
                contentHash = "abc123", sizeBytes = 1117, addedAt = 1_700_000_000_000,
                lastOpenedAt = 1_700_000_100_000, readingStatus = ReadingStatus.READING,
                favorite = true, progress = 0.25f,
            ),
        ),
        categories = listOf(CategoryEntity("cat-1", "Novela", parentId = null, sortOrder = 0)),
        tags = listOf(com.javierrb35.bibliotecaia.data.TagEntity("tag-1", "Clasicos")),
        collections = listOf(com.javierrb35.bibliotecaia.data.CollectionEntity("col-1", "Verano 2026", createdAt = 42)),
        bookCategories = listOf(com.javierrb35.bibliotecaia.data.BookCategoryCrossRef("book-1", "cat-1")),
        bookTags = listOf(com.javierrb35.bibliotecaia.data.BookTagCrossRef("book-1", "tag-1")),
        collectionBooks = listOf(com.javierrb35.bibliotecaia.data.CollectionBookCrossRef("col-1", "book-1")),
        annotations = listOf(
            AnnotationEntity(
                id = "an-1", bookId = "book-1", locator = """{"href":"c1"}""",
                quote = "En un lugar de la Mancha", body = "Arranca célebremente",
                kind = "HIGHLIGHT", color = 1, createdAt = 10, updatedAt = 20, deletedAt = null,
            ),
        ),
        versions = listOf(
            com.javierrb35.bibliotecaia.data.AnnotationVersionEntity(
                id = "v-1", annotationId = "an-1", body = "Versión anterior", color = 1, savedAt = 15,
            ),
        ),
        positions = listOf(ReadingPositionEntity("book-1", """{"href":"c1"}""", 0.25f, 99)),
    )

    @Test
    fun `ida y vuelta conserva todos los datos`() {
        val json = BackupCodec.toJson(sample(), appVersion = "0.1.0", exportedAt = 77)
        val restored = BackupCodec.fromJson(json)

        assertEquals(1, restored.books.size)
        val book = restored.books.single()
        assertEquals("book-1", book.id)
        assertEquals("Don Quijote de la Mancha", book.title)
        assertEquals(BookFormat.EPUB, book.format)
        assertEquals("abc123", book.contentHash)
        assertEquals(ReadingStatus.READING, book.readingStatus)
        assertTrue(book.favorite)
        assertEquals(0.25f, book.progress, 0.0001f)
        // localPath nunca se exporta: es específico del dispositivo
        assertEquals("", book.localPath)

        assertEquals(1, restored.categories.size)
        assertEquals("Novela", restored.categories.single().name)
        assertEquals(1, restored.bookCategories.size)
        assertEquals(1, restored.bookTags.size)
        assertEquals(1, restored.collectionBooks.size)
        assertEquals(1, restored.annotations.size)
        assertEquals("HIGHLIGHT", restored.annotations.single().kind)
        assertEquals(1, restored.versions.size)
        assertEquals("Versión anterior", restored.versions.single().body)
        assertEquals(1, restored.positions.size)
        assertEquals(99, restored.positions.single().updatedAt)
    }

    @Test
    fun `el manifiesto incluye app y version de formato`() {
        val root = org.json.JSONObject(BackupCodec.toJson(sample(), "0.2.0", 5))
        assertEquals("biblioteca-ia", root.getJSONObject("manifest").getString("app"))
        assertEquals(1, root.getJSONObject("manifest").getInt("formatVersion"))
        assertEquals("0.2.0", root.getJSONObject("manifest").getString("appVersion"))
    }

    @Test
    fun `rechaza JSON ajeno o corrupto`() {
        for (bad in listOf("{}", """{"manifest":{"app":"otra-app","formatVersion":1}}""", "no-json", "")) {
            try {
                BackupCodec.fromJson(bad)
                throw AssertionError("Debe rechazar: $bad")
            } catch (expected: IllegalArgumentException) {
                // mensaje orientado al usuario, sin trazas técnicas
                assertTrue(expected.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun `rechaza formato futuro`() {
        val json = BackupCodec.toJson(sample(), "0.1.0", 1)
            .replace("\"formatVersion\": 1", "\"formatVersion\": 99")
        try {
            BackupCodec.fromJson(json)
            throw AssertionError("Debe rechazar formato futuro")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("formato no compatible"))
        }
    }

    @Test
    fun `valores ausentes tolerados con valores por defecto honestos`() {
        val minimal = """{"manifest":{"app":"biblioteca-ia","formatVersion":1}}"""
        val restored = BackupCodec.fromJson(minimal)
        assertTrue(restored.books.isEmpty())
        assertTrue(restored.annotations.isEmpty())
    }

    @Test
    fun `sin portada de datos sensibles en el JSON`() {
        val json = BackupCodec.toJson(sample(), "0.1.0", 1)
        assertFalse(json.contains("localPath"))
        assertFalse(json.contains("/device/only"))
    }
}
