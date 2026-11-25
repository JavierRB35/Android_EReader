package com.javierrb35.bibliotecaia.sync.coordinator

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CoordinatorJournalTest {
    @Test fun pendingRetryConflictAndAtomicDeltaSurviveReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "coordinator-test-" + UUID.randomUUID() + ".db"
        val a = CoordinatorIdentity.generate()
        val b = CoordinatorIdentity.generate(a.libraryId)
        val key = EntityKey(EntityKind.NOTE, "synthetic-note")
        val change = PendingChange(UUID.randomUUID(), key, 0, ChangeAction.PUT, JSONObject().put("body", "A"))
        fun open() = Room.databaseBuilder(context, AppDatabase::class.java, name).build()
        var db = open()
        try {
            CoordinatorJournal(db, a).enqueue(change)
            db.close()
            db = open()
            val journal = CoordinatorJournal(db, a)
            val retry = requireNotNull(journal.next())
            assertEquals(change.operationId, retry.operationId)
            assertEquals(change.payload().toString(), retry.payload().toString())
            journal.result(retry, ApplyOutcome.Applied(1, true))
            assertNull(journal.next())
            val page = DeltaPage(a.libraryId, 1, 1, listOf(DeltaEntity(key, 1, false, change.payload())))
            journal.accept(0, page)
            assertEquals(1L, journal.cursor())
            val other = CoordinatorJournal(db, b)
            assertEquals(0L, other.cursor())
            val otherChange = PendingChange(UUID.randomUUID(), key, 0, ChangeAction.PUT, JSONObject().put("body", "B"))
            other.enqueue(otherChange)
            other.result(otherChange, ApplyOutcome.Conflict(1, false))
            other.accept(0, page)
            assertEquals(1L, other.cursor())
            val conflict = db.coordinatorJournalDao().pendingForKey(b.libraryId.toString(), b.deviceId.toString(), key.kind.wire, key.id)
            assertEquals("CONFLICT", conflict?.status)
            assertEquals("B", JSONObject(conflict!!.payload).getString("body"))
            assertNull(db.coordinatorJournalDao().pendingForKey(a.libraryId.toString(), a.deviceId.toString(), key.kind.wire, key.id))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
