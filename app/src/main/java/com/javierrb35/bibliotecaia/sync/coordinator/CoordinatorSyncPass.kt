package com.javierrb35.bibliotecaia.sync.coordinator

import androidx.room.withTransaction
import com.javierrb35.bibliotecaia.data.*
import com.javierrb35.bibliotecaia.sync.coordinator.credentials.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/** Durable coordinator journal in the SAME Room database as user data. No
 * automatic overwrite of local domain rows or legacy sync; activation requires
 * their transactional mappings, reconciliation and authorized gateway. */
internal class CoordinatorJournal(private val db: AppDatabase, private val identity: CoordinatorIdentity) {
    private val library = identity.libraryId.toString()
    private val device = identity.deviceId.toString()
    private val dao = db.coordinatorJournalDao()

    // May be called inside a domain mutation's Room transaction after mapping
    // its payload and stable key; a conflicting pending edit cannot be discarded.
    suspend fun enqueue(change: PendingChange) = db.withTransaction {
        val prior = dao.pendingForKey(library, device, change.key.kind.wire, change.key.id)
        val payload = change.payload().toString()
        if (prior != null) {
            check(prior.operationId == change.operationId.toString() &&
                prior.expectedRevision == change.expectedRevision &&
                prior.action == change.action.wire && prior.payload == payload) {
                "Hay un cambio sin reconciliar para esta entidad"
            }
        } else {
            val seen = dao.replica(library, device, change.key.kind.wire, change.key.id)?.revision ?: 0L
            check(seen == change.expectedRevision) { "La revisión local ya no es vigente" }
            dao.enqueue(CoordinatorOutbox(library, device, change.operationId.toString(),
                change.key.kind.wire, change.key.id, change.expectedRevision, change.action.wire,
                payload, "PENDING", null, null))
        }
    }

    suspend fun cursor(): Long = dao.cursor(library, device)?.revision ?: 0L
    suspend fun next(): PendingChange? = dao.nextPending(library, device)?.let { row ->
        val action = ChangeAction.entries.firstOrNull { it.wire == row.action } ?: throw ProtocolViolation()
        PendingChange(UUID.fromString(row.operationId), EntityKey(EntityKind.parse(row.kind), row.entityId),
            row.expectedRevision, action, JSONObject(row.payload))
    }

    suspend fun result(change: PendingChange, outcome: ApplyOutcome) = db.withTransaction {
        val row = dao.pendingForKey(library, device, change.key.kind.wire, change.key.id)
        check(row?.operationId == change.operationId.toString() && row.status == "PENDING")
        val (status, rev, deleted) = when (outcome) {
            is ApplyOutcome.Applied -> Triple("ACK", outcome.revision, null)
            is ApplyOutcome.Conflict -> Triple("CONFLICT", outcome.revision, outcome.deleted)
        }
        check(dao.recordResult(library, device, change.operationId.toString(), status, rev, deleted) == 1)
        // ACK is retained until the corresponding revision has been pulled.
    }

    suspend fun accept(after: Long, page: DeltaPage) = db.withTransaction {
        check(page.libraryId == identity.libraryId && cursor() == after && page.cursor >= after)
        for (entity in page.entities) {
            val old = dao.replica(library, device, entity.key.kind.wire, entity.key.id)
            check(old == null || entity.revision > old.revision)
            dao.saveReplica(CoordinatorReplica(library, device, entity.key.kind.wire, entity.key.id,
                entity.revision, entity.deleted, entity.payload().toString()))
            dao.clearAcknowledged(library, device, entity.key.kind.wire, entity.key.id, entity.revision)
        }
        dao.saveCursor(CoordinatorCursor(library, device, page.cursor))
        // No domain tables changed here. A future mapper must materialize the
        // accepted entities in the same transaction before user-facing activation.
    }
}

/** Single bounded pass. Retry after process restart with the exact same stored
 * operationId; network failure never acknowledges it or advances a cursor.
 * A conflict remains durable and requires explicit resolution. */
internal class CoordinatorSyncPass(
    private val db: AppDatabase,
    private val enrollment: CoordinatorEnrollment,
    private val client: CoordinatorClient
) {
    companion object { private val passLock = Mutex() }
    suspend fun run(maxWrites: Int = 20, maxPages: Int = 5, pageSize: Int = 10): Long =
        passLock.withLock {
            withContext(Dispatchers.IO) {
                require(maxWrites in 1..100 && maxPages in 1..100 && pageSize in 1..10)
                val state = enrollment.current() ?: throw CredentialStoreFailure()
                check(state.phase == EnrollmentPhase.ACTIVE)
                val id = state.identity()
                val journal = CoordinatorJournal(db, id)
                for (i in 0 until maxWrites) {
                    val change = journal.next() ?: break
                    journal.result(change, client.apply(id, change))
                }
                var cursor = journal.cursor()
                for (i in 0 until maxPages) {
                    val page = client.pull(id, cursor, pageSize)
                    journal.accept(cursor, page)
                    cursor = page.cursor
                    if (cursor == page.head) break
                }
                cursor
            }
        }
}
