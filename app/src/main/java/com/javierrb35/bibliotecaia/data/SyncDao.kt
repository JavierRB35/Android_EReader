package com.javierrb35.bibliotecaia.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/** Cambio local pendiente de empujar a Supabase (Incremento 5-6). */
@Entity(tableName = "sync_queue", primaryKeys = ["entityType", "entityId"])
data class SyncQueueEntry(
    val entityType: String,   // BOOKS | ANNOTATIONS
    val entityId: String,     // UUID estable compartido con el remoto
    val operation: String,    // UPSERT
    val queuedAt: Long,
    val attempts: Int = 0,        // intentos fallidos; la entrada JAMÁS se borra por error
    val lastError: String? = null // último error HTTP, visible en diagnóstico
)

@Dao
interface SyncQueueDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(entry: SyncQueueEntry)

    @Query("SELECT * FROM sync_queue ORDER BY queuedAt ASC")
    suspend fun all(): List<SyncQueueEntry>

    @Query("SELECT COUNT(*) FROM sync_queue")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM sync_queue WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun clear(entityType: String, entityId: String)

    @Query("DELETE FROM sync_queue WHERE entityType = :entityType AND entityId IN (:entityIds)")
    suspend fun clearMany(entityType: String, entityIds: List<String>)

    /** Registra el fallo sin descartar el cambio pendiente (revisión del propietario). */
    @Query("UPDATE sync_queue SET attempts = attempts + 1, lastError = :error WHERE entityType = :entityType AND entityId = :entityId")
    suspend fun markFailed(entityType: String, entityId: String, error: String)
}

/**
 * Catálogo de entidades sincronizables y construcción de filas remotas (sin campos
 * específicos de dispositivo). Puro y testeable.
 */
object SyncRows {

    const val BOOKS = "BOOKS"
    const val ANNOTATIONS = "ANNOTATIONS"
    const val REMOTE_BOOKS = "books"
    const val REMOTE_ANNOTATIONS = "annotations"

    fun bookJson(book: BookEntity): JSONObject = JSONObject()
        .put("id", book.id)
        .put("title", book.title)
        .put("authors", book.authors ?: JSONObject.NULL)
        .put("format", book.format.name)
        .put("file_name", book.fileName)
        .put("content_hash", book.contentHash)
        .put("size_bytes", book.sizeBytes)
        .put("added_at", iso(book.addedAt))
        .put("last_opened_at", book.lastOpenedAt?.let { iso(it) } ?: JSONObject.NULL)
        .put("reading_status", book.readingStatus.name)
        .put("favorite", book.favorite)
        .put("progress", book.progress.toDouble())

    fun annotationJson(a: AnnotationEntity): JSONObject = JSONObject()
        .put("id", a.id)
        .put("book_id", a.bookId ?: JSONObject.NULL)
        .put("locator", a.locator?.let { JSONObject(it) } ?: JSONObject.NULL)
        .put("quote", a.quote)
        .put("body", a.body)
        .put("kind", a.kind)
        .put("color", a.color)
        .put("deleted_at", a.deletedAt?.let { iso(it) } ?: JSONObject.NULL)
        .put("created_at", iso(a.createdAt))
        .put("updated_at", iso(a.updatedAt))

    fun iso(epochMs: Long): String = java.time.Instant.ofEpochMilli(epochMs).toString()
}
