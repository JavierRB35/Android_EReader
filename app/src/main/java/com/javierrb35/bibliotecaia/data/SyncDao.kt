package com.javierrb35.bibliotecaia.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Tabla histórica conservada para compatibilidad de Room y preservación de datos.
 * No se envía a ningún servidor ni forma parte de la sincronización SAF vigente. */
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
