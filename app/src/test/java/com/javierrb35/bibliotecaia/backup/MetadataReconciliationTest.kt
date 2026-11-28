package com.javierrb35.bibliotecaia.backup

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.BookSynopsisEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regresión permanente del defecto #155: 483 EPUB importados con
 * title="epub" y authors="Desktop". La reconciliación completa y corrige
 * fichas existentes por SHA-256 sin tocar ediciones del usuario ni datos
 * de lectura, y converge de forma idempotente entre dispositivos (#142).
 */
class MetadataReconciliationTest {

    private fun book(id: String, title: String, authors: String?, fileName: String = "archivo.epub") =
        BookEntity(id, title, authors, BookFormat.EPUB, fileName,
            localPath = "", contentHash = "ab".repeat(32), sizeBytes = 10, addedAt = 1)

    private val hash = "ab".repeat(32)

    // ---------- Detección de placeholders ----------

    @Test fun folderAndFileNameArtifactsArePlaceholders() {
        assertTrue(BookMetadataPlaceholders.isPlaceholderTitle("epub", "libro.epub"))
        assertTrue(BookMetadataPlaceholders.isPlaceholderTitle("EPUB", "x.epub"))
        assertTrue(BookMetadataPlaceholders.isPlaceholderTitle("  ", "x.epub"))
        assertTrue(BookMetadataPlaceholders.isPlaceholderTitle("Desktop", "Desktop.epub"))
        assertTrue(BookMetadataPlaceholders.isPlaceholderTitle("Mi Novela", "Mi Novela.epub"))
        assertFalse(BookMetadataPlaceholders.isPlaceholderTitle("La Sombra del Viento", "archivo.epub"))
    }

    @Test fun genericAuthorsArePlaceholdersButRealNamesAreNot() {
        assertTrue(BookMetadataPlaceholders.isPlaceholderAuthors(null))
        assertTrue(BookMetadataPlaceholders.isPlaceholderAuthors(""))
        assertTrue(BookMetadataPlaceholders.isPlaceholderAuthors("Desktop"))
        assertTrue(BookMetadataPlaceholders.isPlaceholderAuthors(" DESKTOP "))
        assertTrue(BookMetadataPlaceholders.isPlaceholderAuthors("N/A"))
        assertFalse(BookMetadataPlaceholders.isPlaceholderAuthors("Ana Ruiz"))
        assertFalse(BookMetadataPlaceholders.isPlaceholderAuthors("Desktop Publishing House"))
    }

    // ---------- Plan de reparación ----------

    @Test fun placeholderTitleAndAuthorsAreReplacedByRealMetadata() {
        val existing = book("1", "epub", "Desktop")
        val incoming = book("9", "La Sombra del Viento", "Ana Ruiz")
        val plan = MetadataReconciler.plan(existing, incoming)
        assertEquals("La Sombra del Viento", plan.title)
        assertEquals("Ana Ruiz", plan.authors)
        val repaired = MetadataReconciler.apply(existing, plan)
        assertEquals("La Sombra del Viento", repaired.title)
        assertEquals("Ana Ruiz", repaired.authors)
        // Identificadores, hash y demás campos intactos.
        assertEquals(existing.id, repaired.id)
        assertEquals(existing.contentHash, repaired.contentHash)
        assertEquals(existing.progress, repaired.progress)
    }

    @Test fun userEditedTitleIsNeverOverwritten() {
        val existing = book("1", "Edición del usuario", "Ana Ruiz")
        val incoming = book("9", "Título del catálogo", "Otra Autora")
        val plan = MetadataReconciler.plan(existing, incoming)
        assertNull(plan.title)
        assertNull(plan.authors)
        assertFalse(plan.hasChanges)
    }

    @Test fun repairedRecordConvergesIdempotently() {
        val placeholder = book("1", "epub", "Desktop")
        val corrected = book("9", "Real", "Autor Real")
        val first = MetadataReconciler.apply(placeholder, MetadataReconciler.plan(placeholder, corrected))
        val second = MetadataReconciler.apply(first, MetadataReconciler.plan(first, corrected))
        assertEquals(first, second)
        assertFalse(MetadataReconciler.plan(first, corrected).hasChanges)
    }

    @Test fun differentHashOrEqualValuesDoNotRepair() {
        val existing = book("1", "epub", "Desktop")
        val other = book("9", "Otro", "Alguien").copy(contentHash = "cd".repeat(32))
        assertFalse(MetadataReconciler.plan(existing, other).hasChanges)
        val same = book("9", "epub", "Desktop")
        assertFalse(MetadataReconciler.plan(existing, same).hasChanges)
    }

    @Test fun filenameFallbackTitleIsStillPlaceholderSoCatalogueCanFixIt() {
        val existing = book("1", "novela-descargada", "Desktop", fileName = "novela-descargada.epub")
        val incoming = book("9", "Novela Descargada", "Autor")
        val plan = MetadataReconciler.plan(existing, incoming)
        assertEquals("Novela Descargada", plan.title)
        assertEquals("Autor", plan.authors)
    }

    // ---------- Sinopsis importada ----------

    @Test fun importedSynopsisOnlyFillsEmptyText() {
        assertTrue(MetadataReconciler.importedSynopsisMayReplace(null))
        assertTrue(MetadataReconciler.importedSynopsisMayReplace("  "))
        assertFalse(MetadataReconciler.importedSynopsisMayReplace("Argumento escrito por el usuario"))
    }

    // ---------- Códec: metadatos extendidos del migrador ----------

    @Test fun codecAcceptsMigratorExtrasAndImportedSynopsis() {
        // Estructura equivalente a la que emite CalibreMigrator-2 (#155).
        val json = """
        {"manifest":{"app":"biblioteca-ia","formatVersion":2,"appVersion":"CalibreMigrator-2",
          "exportedAt":1,"counts":{"books":1,"annotations":0,"versions":0,"positions":0,"synopses":1}},
         "books":[{"id":"book-1","title":"La Sombra del Viento","authors":"Ana Ruiz","format":"EPUB",
           "file_name":"x.epub","content_hash":"${hash}","size_bytes":10,"added_at":1,
           "last_opened_at":null,"reading_status":"PENDING","favorite":false,"progress":0.0}],
         "categories":[],"tags":[{"id":"tag-1","name":"Aventura"}],"collections":[],
         "bookCategories":[],"bookTags":[{"first":"book-1","second":"tag-1"}],
         "collectionBooks":[],"annotations":[],"annotationVersions":[],"readingPositions":[],
         "bookSynopses":[{"book_id":"book-1","text":"Argumento del catálogo",
           "provider":"${MetadataReconciler.IMPORTED_SYNOPSIS_PROVIDER}","generated_at":5,"spoiler_safe":false}],
         "deletedBooks":[],
         "bookExtendedMetadata":[{"id":"book-1","content_hash":"${hash}",
           "fields":[["idioma","es"],["editorial","Sur"]]}],
         "covers":[{"book":"${hash}","image":"ff"}]}
        """.trimIndent()
        val snapshot = BackupCodec.fromJson(json)
        assertEquals(1, snapshot.books.size)
        assertEquals("La Sombra del Viento", snapshot.books.single().title)
        assertEquals(1, snapshot.tags.size)
        assertEquals(1, snapshot.synopses.size)
        val synopsis = snapshot.synopses.single()
        assertEquals(MetadataReconciler.IMPORTED_SYNOPSIS_PROVIDER, synopsis.provider)
        assertFalse(synopsis.spoilerSafe)
        // La reparación sobre la ficha genérica local sustituye los placeholders
        // por el registro real recibido.
        val existing = book("local-1", "epub", "Desktop")
        val plan = MetadataReconciler.plan(existing, snapshot.books.single())
        assertTrue(plan.hasChanges)
        assertEquals("La Sombra del Viento", plan.title)
        assertEquals("Ana Ruiz", plan.authors)
    }
}
