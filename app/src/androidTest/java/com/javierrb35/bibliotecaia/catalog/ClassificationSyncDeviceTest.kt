package com.javierrb35.bibliotecaia.catalog

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.backup.BackupManager
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookClassificationEntity
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Sincronización A/B del estado de catalogación por SHA-256 (#142). */
@RunWith(AndroidJUnit4::class)
class ClassificationSyncDeviceTest {

    private fun book(id: String) = BookEntity(
        id = id, title = "Libro compartido", authors = "Autor/a",
        format = BookFormat.EPUB, fileName = "libro.epub", localPath = "",
        contentHash = id.replace("-", "").padStart(64, '0').take(64),
        sizeBytes = 1, addedAt = 1)

    @Test fun classificationTravelsBetweenDevicesAndNewestWins() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deviceA = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val deviceB = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val id = UUID.randomUUID().toString()
            deviceA.bookDao().insert(book(id))
            deviceB.bookDao().insert(book(id))

            // A cataloga y publica su snapshot.
            deviceA.classificationDao().save(BookClassificationEntity(
                id, BookClassificationEntity.STATUS_CLASSIFIED,
                BookClassificationEntity.BASIS_AI, "gemini", 1_000))
            val published = BackupManager(deviceA, "test").exportJson()

            // B sincroniza: el estado llega por hash, no por id de dispositivo.
            BackupManager(deviceB, "test").restoreJson(published, synchronize = true)
            assertEquals(BookClassificationEntity.STATUS_CLASSIFIED,
                deviceB.classificationDao().byBook(id)?.status)
            assertEquals("gemini", deviceB.classificationDao().byBook(id)?.provider)

            // B revisa la ficha más tarde: su marca más reciente gana al volver a sincronizar.
            deviceB.classificationDao().save(BookClassificationEntity(
                id, BookClassificationEntity.STATUS_REVIEW,
                BookClassificationEntity.BASIS_AI, "groq", 2_000))
            BackupManager(deviceA, "test").restoreJson(
                BackupManager(deviceB, "test").exportJson(), synchronize = true)
            assertEquals(BookClassificationEntity.STATUS_REVIEW,
                deviceA.classificationDao().byBook(id)?.status)
            assertEquals("groq", deviceA.classificationDao().byBook(id)?.provider)

            // Una restauración normal (no sincronización) nunca baja el estado.
            deviceA.classificationDao().save(BookClassificationEntity(
                id, BookClassificationEntity.STATUS_PENDING,
                BookClassificationEntity.BASIS_NONE, null, 3_000))
            BackupManager(deviceA, "test").restoreJson(published, synchronize = false)
            assertEquals(BookClassificationEntity.STATUS_PENDING,
                deviceA.classificationDao().byBook(id)?.status)
        } finally {
            deviceA.close()
            deviceB.close()
        }
    }

    @Test fun deletedBookAlsoRemovesItsClassificationOnRestore() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val deviceA = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val deviceB = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val id = UUID.randomUUID().toString()
            deviceA.bookDao().insert(book(id))
            deviceA.classificationDao().save(BookClassificationEntity(
                id, BookClassificationEntity.STATUS_CLASSIFIED,
                BookClassificationEntity.BASIS_AI, "gemini", 1_000))
            val published = BackupManager(deviceA, "test").exportJson()
            BackupManager(deviceB, "test").restoreJson(published, synchronize = true)
            assertNotNull(deviceB.classificationDao().byBook(id))

            // A elimina el libro: la baja debe arrastrar también la clasificación.
            com.javierrb35.bibliotecaia.data.BookDeletion.remove(deviceA, id)
            BackupManager(deviceB, "test").restoreJson(
                BackupManager(deviceA, "test").exportJson(), synchronize = true)
            assertNull(deviceB.bookDao().findById(id))
            assertNull(deviceB.classificationDao().byBook(id))
        } finally {
            deviceA.close()
            deviceB.close()
        }
    }
}
