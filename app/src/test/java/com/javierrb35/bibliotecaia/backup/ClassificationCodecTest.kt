package com.javierrb35.bibliotecaia.backup

import com.javierrb35.bibliotecaia.data.BookClassificationEntity
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Sincronización del estado de catalogación dentro del snapshot v2 (#142). */
class ClassificationCodecTest {

    private fun book(id: String) = BookEntity(
        id = id, title = "Libro", authors = null, format = BookFormat.EPUB,
        fileName = "libro.epub", localPath = "", contentHash = id.replace("-", ""),
        sizeBytes = 1, addedAt = 1)

    private fun snapshot(classifications: List<BookClassificationEntity>) = LibrarySnapshot(
        books = classifications.map { book(it.bookId) },
        categories = emptyList(), tags = emptyList(), collections = emptyList(),
        bookCategories = emptyList(), bookTags = emptyList(), collectionBooks = emptyList(),
        annotations = emptyList(), versions = emptyList(), positions = emptyList(),
        classifications = classifications,
    )

    @Test fun classificationsRoundTripThroughJson() {
        val rows = listOf(
            BookClassificationEntity("b1", BookClassificationEntity.STATUS_CLASSIFIED,
                BookClassificationEntity.BASIS_AI, "gemini", 100),
            BookClassificationEntity("b2", BookClassificationEntity.STATUS_REVIEW,
                BookClassificationEntity.BASIS_METADATA, null, 200),
        )
        val json = BackupCodec.toJson(snapshot(rows), "test", 123)
        val decoded = BackupCodec.fromJson(json)
        assertEquals(rows.sortedBy { it.bookId }, decoded.classifications.sortedBy { it.bookId })
        // El manifiesto declara la sección y se serializa ordenada.
        assertEquals(2, JSONObject(json).getJSONObject("manifest")
            .getJSONObject("counts").getInt("classifications"))
    }

    @Test fun legacySnapshotWithoutClassificationsStillParses() {
        val json = BackupCodec.toJson(snapshot(emptyList()), "test", 123)
        JSONObject(json).apply {
            getJSONObject("manifest").getJSONObject("counts").remove("classifications")
            remove("bookClassifications")
        }.toString().let { legacy ->
            assertTrue(BackupCodec.fromJson(legacy).classifications.isEmpty())
        }
    }

    @Test fun declaredCountMismatchIsRejected() {
        val json = BackupCodec.toJson(snapshot(emptyList()), "test", 123)
        val tampered = JSONObject(json).apply {
            getJSONObject("manifest").getJSONObject("counts").put("classifications", 5)
        }.toString()
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.fromJson(tampered) }
    }

    @Test fun invalidStatusOrBasisIsRejected() {
        val badStatus = JSONObject(BackupCodec.toJson(snapshot(emptyList()), "test", 123)).apply {
            put("bookClassifications", org.json.JSONArray().put(
                JSONObject().put("book_id", "b1").put("status", "no-existe")
                    .put("basis", "ai").put("processed_at", 1)))
        }.toString()
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.fromJson(badStatus) }

        val badBasis = JSONObject(BackupCodec.toJson(snapshot(emptyList()), "test", 123)).apply {
            put("bookClassifications", org.json.JSONArray().put(
                JSONObject().put("book_id", "b1").put("status", "review")
                    .put("basis", "adivinado").put("processed_at", 1)))
        }.toString()
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.fromJson(badBasis) }
    }

    @Test fun classificationForUnknownBookParsesButFailsRestoreValidation() {
        val json = BackupCodec.toJson(snapshot(emptyList()), "test", 123)
        val orphan = JSONObject(json).apply {
            getJSONObject("manifest").getJSONObject("counts").put("classifications", 1)
            put("bookClassifications", org.json.JSONArray().put(
                JSONObject().put("book_id", UUID.randomUUID().toString())
                    .put("status", "review").put("basis", "ai").put("processed_at", 1)))
        }.toString()
        // El códec solo valida estructura; la referencia huérfana se rechaza en
        // BackupManager.restoreJson, que sí conoce los libros existentes.
        assertTrue(BackupCodec.fromJson(orphan).classifications.isNotEmpty())
    }
}
