package com.javierrb35.bibliotecaia.ai

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.backup.BackupCodec
import com.javierrb35.bibliotecaia.backup.LibrarySnapshot
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookSynopsisDeviceTest {
    private fun book(id: String) = BookEntity(
        id = id, title = "El libro de prueba", authors = "Una autora",
        format = BookFormat.EPUB, fileName = "sample.epub",
        localPath = "", contentHash = id.replace("-", ""),
        sizeBytes = 5, addedAt = 1
    )

    @Test fun generatedSynopsisIsPersistedAndTagsAreOnlyAssignedWhenBookIsUntagged() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val first = UUID.randomUUID().toString()
            val second = UUID.randomUUID().toString()
            db.bookDao().insert(book(first))
            db.bookDao().insert(book(second))
            val repository = BookSynopsisRepository(db)
            assertEquals(listOf("Aventura", "Novela histórica"),
                repository.save(first, "Argumento basado en el libro [1]", "gemini",
                    spoilerSafe = true, suggestedTags = listOf("Aventura", "Novela histórica")))
            assertEquals("Argumento basado en el libro [1]",
                db.synopsisDao().byBook(first)?.text)
            assertTrue(db.synopsisDao().byBook(first)!!.spoilerSafe)
            assertEquals(listOf("Aventura", "Novela histórica"),
                db.refsDao().tagNamesForBook(first))
            assertTrue(db.refsDao().tagIdsForBook(second).isEmpty())

            // Regenerate overview: keep the reader's existing assignments.
            assertTrue(repository.save(first, "Revisado [2]", "groq",
                spoilerSafe = false, suggestedTags = listOf("Fantasía")).isEmpty())
            assertEquals("Revisado [2]", db.synopsisDao().byBook(first)?.text)
            assertFalse(db.synopsisDao().byBook(first)!!.spoilerSafe)
            assertEquals(listOf("Aventura", "Novela histórica"),
                db.refsDao().tagNamesForBook(first))
        } finally {
            db.close()
        }
    }

    @Test fun synopsisSurvivesBackupMetadataAndLegacyBackupWithoutSynopsisStillOpens() {
        val id = UUID.randomUUID().toString()
        val book = book(id)
        val overview = com.javierrb35.bibliotecaia.data.BookSynopsisEntity(
            id, "Argumento persistente", "gemini", 123, true)
        val snapshot = LibrarySnapshot(
            books = listOf(book), categories = emptyList(), tags = emptyList(),
            collections = emptyList(), bookCategories = emptyList(), bookTags = emptyList(),
            collectionBooks = emptyList(), annotations = emptyList(),
            versions = emptyList(), positions = emptyList(), synopses = listOf(overview)
        )
        val json = BackupCodec.toJson(snapshot, "test", 123)
        assertEquals(listOf(overview), BackupCodec.fromJson(json).synopses)
        val older = JSONObject(json).apply {
            getJSONObject("manifest").put("formatVersion", 1)
                .getJSONObject("counts").remove("synopses")
            remove("bookSynopses")
        }
        assertTrue(BackupCodec.fromJson(older.toString()).synopses.isEmpty())
    }
}
