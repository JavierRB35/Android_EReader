package com.javierrb35.bibliotecaia.sync

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.backup.BackupManager
import com.javierrb35.bibliotecaia.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookDeletionJourneyTest {
    @Test fun oldPeerCannotResurrectDeletedBook() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val a = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val book = BookEntity("one", "Libro", null, BookFormat.EPUB, "one.epub", "", "a".repeat(64), 1, 1)
        try {
            a.bookDao().insert(book)
            val old = BackupManager(a, "test").exportJson()
            BackupManager(b, "test").restoreJson(old, synchronize = true)
            BookDeletion.remove(a, book.id)
            val removed = BackupManager(a, "test").exportJson()
            BackupManager(b, "test").restoreJson(removed, synchronize = true)
            BackupManager(a, "test").restoreJson(old, synchronize = true)
            BackupManager(b, "test").restoreJson(old, synchronize = true)
            assertNull(a.bookDao().findByHash(book.contentHash))
            assertNull(b.bookDao().findByHash(book.contentHash))
            assertEquals(1, b.backupDao().deletedBooksAll().size)
        } finally { a.close(); b.close() }
    }
}
