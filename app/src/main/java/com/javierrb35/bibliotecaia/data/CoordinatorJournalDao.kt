package com.javierrb35.bibliotecaia.data

import androidx.room.*

@Entity(tableName = "coordinator_cursor", primaryKeys = ["libraryId", "deviceId"])
data class CoordinatorCursor(val libraryId: String, val deviceId: String, val revision: Long)

@Entity(tableName = "coordinator_replica", primaryKeys = ["libraryId", "deviceId", "kind", "entityId"])
data class CoordinatorReplica(val libraryId: String, val deviceId: String, val kind: String,
    val entityId: String, val revision: Long, val deleted: Boolean, val payload: String)

@Entity(tableName = "coordinator_outbox", primaryKeys = ["libraryId", "deviceId", "operationId"],
    indices = [Index(value = ["libraryId", "deviceId", "kind", "entityId"], unique = true)])
data class CoordinatorOutbox(val libraryId: String, val deviceId: String, val operationId: String,
    val kind: String, val entityId: String, val expectedRevision: Long, val action: String,
    val payload: String, val status: String, val resultRevision: Long?, val resultDeleted: Boolean?)

@Dao
interface CoordinatorJournalDao {
    @Query("SELECT * FROM coordinator_cursor WHERE libraryId=:library AND deviceId=:device")
    suspend fun cursor(library: String, device: String): CoordinatorCursor?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveCursor(row: CoordinatorCursor)

    @Query("SELECT * FROM coordinator_replica WHERE libraryId=:library AND deviceId=:device AND kind=:kind AND entityId=:id")
    suspend fun replica(library: String, device: String, kind: String, id: String): CoordinatorReplica?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveReplica(row: CoordinatorReplica)

    @Query("SELECT * FROM coordinator_outbox WHERE libraryId=:library AND deviceId=:device AND kind=:kind AND entityId=:id")
    suspend fun pendingForKey(library: String, device: String, kind: String, id: String): CoordinatorOutbox?
    @Query("SELECT * FROM coordinator_outbox WHERE libraryId=:library AND deviceId=:device AND status='PENDING' ORDER BY rowid LIMIT 1")
    suspend fun nextPending(library: String, device: String): CoordinatorOutbox?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun enqueue(row: CoordinatorOutbox)
    @Query("UPDATE coordinator_outbox SET status=:status, resultRevision=:revision, resultDeleted=:deleted WHERE libraryId=:library AND deviceId=:device AND operationId=:operation AND status='PENDING'")
    suspend fun recordResult(library: String, device: String, operation: String, status: String, revision: Long, deleted: Boolean?): Int
    @Query("DELETE FROM coordinator_outbox WHERE libraryId=:library AND deviceId=:device AND kind=:kind AND entityId=:id AND status='ACK' AND resultRevision<=:revision")
    suspend fun clearAcknowledged(library: String, device: String, kind: String, id: String, revision: Long)
}
