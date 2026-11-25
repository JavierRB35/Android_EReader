package com.javierrb35.bibliotecaia.sync

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Same production journal, inventory, integrity and merge code; independent
 * Room databases and a shared test folder. Does not assert Google Drive support. */
@RunWith(AndroidJUnit4::class)
class SharedLibraryJourneyTest {
    private class TestStore(private val dir: File) : SharedStore {
        override fun list() = dir.listFiles().orEmpty().filter { it.isFile }.map {
            SharedDocumentStore.Item(it.name, Uri.fromFile(it), it.length())
        }
        override fun open(item: SharedDocumentStore.Item): InputStream = File(item.uri.path!!).inputStream()
        override fun create(name: String, mime: String, source: InputStream, hash: String, size: Long): SharedDocumentStore.Item {
            val file = File(dir, name)
            check(file.createNewFile())
            source.use { input -> file.outputStream().use { input.copyTo(it) } }
            val item = SharedDocumentStore.Item(name, Uri.fromFile(file), file.length())
            check(verify(item, hash, size))
            return item
        }
    }
    @Test fun emptiedFolderRebuildsFromLocalBooksWithoutResettingDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "rebuild-" + UUID.randomUUID()).apply { mkdirs() }
        val folder = File(root, "cloud").apply { mkdirs() }
        val store = TestStore(folder)
        val a = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val original = File(root, "original.epub").apply { writeText("book bytes") }
        val book = BookEntity("book", "Libro", null, BookFormat.EPUB, original.name, original.absolutePath,
            SharedDocumentStore.hash(original.readBytes()), original.length(), 1L)
        try {
            a.bookDao().insert(book)
            a.readingDao().savePosition(ReadingPositionEntity(book.id, "page:3", .3f, 100L))
            assertTrue(SharedLibrary.syncStore(store, a, "a".repeat(32)).errors.isEmpty())
            folder.listFiles()!!.forEach { assertTrue(it.delete()) }
            assertTrue(store.list().isEmpty())
            assertEquals(setOf(book.contentHash), SharedLibrary.syncStore(store, a, "a".repeat(32)).hashes)
            assertEquals(setOf(book.contentHash), SharedLibrary.syncStore(store, b, "b".repeat(32)).hashes)
            assertEquals("page:3", b.readingDao().position(book.id)!!.locator)
            val downloaded = File(root, "downloaded.epub")
            SharedLibrary.stageFromStore(store, book, downloaded)
            assertArrayEquals(original.readBytes(), downloaded.readBytes())
            assertEquals(1, folder.listFiles()!!.count { it.name.startsWith("bia-book-") })
        } finally { a.close(); b.close(); root.deleteRecursively() }
    }

    @Test fun independentDevicesKeepBooksAndReconcilePositionsAndNotes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "shared-journey-" + UUID.randomUUID()).apply { mkdirs() }
        val folder = File(root, "cloud").apply { mkdirs() }
        val store = TestStore(folder)
        val a = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val ownerA = "a".repeat(32)
        val ownerB = "b".repeat(32)
        fun local(id: String, text: String): BookEntity {
            val file = File(root, id + ".epub").apply { writeText(text) }
            return BookEntity(id, id, null, BookFormat.EPUB, file.name, file.absolutePath,
                SharedDocumentStore.hash(file.readBytes()), file.length(), 1L)
        }
        try {
            val first = local("first", "epub original one")
            val second = local("second", "epub original two")
            a.bookDao().insert(first)
            b.bookDao().insert(second)
            a.readingDao().savePosition(ReadingPositionEntity(first.id, "page:10", .5f, 100L))
            val note = AnnotationEntity("note", first.id, "page:10", "text", "original", "NOTE", 0, 1L, 100L)
            a.readingDao().insertAnnotation(note)
            assertTrue(SharedLibrary.syncStore(store, a, ownerA).errors.isEmpty())
            assertEquals(setOf(first.contentHash, second.contentHash), SharedLibrary.syncStore(store, b, ownerB).hashes)
            val remote = b.bookDao().findByHash(first.contentHash)!!
            assertTrue(remote.localPath.isBlank())
            val downloaded = File(root, "downloaded.epub")
            SharedLibrary.stageFromStore(store, remote, downloaded)
            assertArrayEquals(File(first.localPath).readBytes(), downloaded.readBytes())
            b.readingDao().savePosition(ReadingPositionEntity(remote.id, "page:2", .1f, 200L))
            b.readingDao().updateAnnotation(note.copy(bookId = remote.id, deletedAt = 201L, updatedAt = 201L))
            assertTrue(SharedLibrary.syncStore(store, b, ownerB).errors.isEmpty())
            assertEquals(setOf(first.contentHash, second.contentHash), SharedLibrary.syncStore(store, a, ownerA).hashes)
            assertEquals(.1f, a.readingDao().position(first.id)!!.progress, 0f)
            assertEquals(201L, a.readingDao().annotation(note.id)!!.deletedAt)
            assertTrue(File(first.localPath).delete())
            assertEquals(1, a.bookDao().clearLocalPath(first.id, first.localPath))
            assertTrue(SharedLibrary.syncStore(store, a, ownerA).errors.isEmpty())
            val restored = File(root, "restored.epub")
            SharedLibrary.stageFromStore(store, first, restored)
            assertArrayEquals(downloaded.readBytes(), restored.readBytes())
            assertEquals(2, folder.listFiles()!!.count { it.name.startsWith("bia-book-") })
            // An incomplete, unreceipted object must never become downloadable.
            File(folder, "bia-book-" + "f".repeat(64) + "-" + UUID.randomUUID() + ".bin").writeText("partial")
            assertEquals(setOf(first.contentHash, second.contentHash), SharedLibrary.syncStore(store, a, ownerA).hashes)
        } finally { a.close(); b.close(); root.deleteRecursively() }
    }
}
