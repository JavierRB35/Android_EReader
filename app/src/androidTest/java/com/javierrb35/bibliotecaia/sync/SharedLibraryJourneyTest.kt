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
    @Test fun namedOriginalsKeepReadableNamesAndReuseByHashWithoutOverwriting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "named-originals-" + UUID.randomUUID()).apply { mkdirs() }
        val folder = File(root, "cloud").apply { mkdirs() }
        val store = TestStore(folder)
        val first = File(root, "Don Quijote.epub").apply { writeText("first bytes") }
        val other = File(root, "different.epub").apply { writeText("other bytes") }
        fun book(id: String, file: File) = BookEntity(id, "Libro", null, BookFormat.EPUB,
            first.name, file.absolutePath, SharedDocumentStore.hash(file.readBytes()), file.length(), 1L)
        try {
            val a = book("a", first)
            val b = book("b", other)
            val item = SharedNamedOriginals.ensure(store, a, first)
            assertEquals(first.name, item.name)
            assertEquals(item, SharedNamedOriginals.ensure(store, a.copy(fileName = "Otro título.epub"), first))
            val distinct = SharedNamedOriginals.ensure(store, b, other)
            assertEquals("Don Quijote (2).epub", distinct.name)
            assertArrayEquals(first.readBytes(), File(folder, first.name).readBytes())
            assertArrayEquals(other.readBytes(), File(folder, distinct.name).readBytes())
            assertEquals(2, store.list().size)
            assertThrows(IllegalArgumentException::class.java) {
                SharedNamedOriginals.ensure(store, a.copy(fileName = "../escape.epub"), first)
            }
            assertThrows(IllegalArgumentException::class.java) {
                SharedNamedOriginals.ensure(store, a, other)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun interruptedPublicationReusesVerifiedOriginalWithoutTrustingFilename() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "dedup-retry-" + UUID.randomUUID()).apply { mkdirs() }
        val folder = File(root, "cloud").apply { mkdirs() }
        val store = TestStore(folder)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val receiver = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val bytes = "valid original bytes".toByteArray()
        val file = File(root, "original.epub").apply { writeBytes(bytes) }
        val hash = SharedDocumentStore.hash(bytes)
        val book = BookEntity("retry", "Libro", null, BookFormat.EPUB, file.name,
            file.absolutePath, hash, file.length(), 1L)
        val wrong = File(folder, "bia-book-" + hash + "-00000000-0000-0000-0000-000000000001.bin")
            .apply { writeBytes(ByteArray(bytes.size) { 0 }) }
        val good = File(folder, "bia-book-" + hash + "-00000000-0000-0000-0000-000000000002.bin")
            .apply { writeBytes(bytes) }
        try {
            db.bookDao().insert(book)
            // Both objects predate the catalogue, as after a crash. The first
            // candidate has the correct name and size but incorrect contents.
            assertEquals(setOf(hash), SharedLibrary.syncStore(store, db, "a".repeat(32)).hashes)
            assertEquals(2, folder.listFiles()!!.count { it.name.startsWith("bia-book-") })
            assertEquals(setOf(hash), SharedLibrary.syncStore(store, receiver, "b".repeat(32)).hashes)
            val restored = File(root, "restored.epub")
            SharedLibrary.stageFromStore(store, book, restored)
            assertArrayEquals(bytes, restored.readBytes())
            assertTrue(wrong.isFile)
            assertTrue(good.isFile)
        } finally { db.close(); receiver.close(); root.deleteRecursively() }
    }

    @Test fun corruptUnpublishedOriginalIsNotAdopted() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "dedup-corrupt-" + UUID.randomUUID()).apply { mkdirs() }
        val folder = File(root, "cloud").apply { mkdirs() }
        val store = TestStore(folder)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File(root, "original.pdf").apply { writeText("valid original") }
        val hash = SharedDocumentStore.hash(file.readBytes())
        val book = BookEntity("new", "Libro", null, BookFormat.PDF, file.name,
            file.absolutePath, hash, file.length(), 1L)
        File(folder, "bia-book-" + hash + "-00000000-0000-0000-0000-000000000001.bin")
            .writeBytes(ByteArray(file.length().toInt()))
        try {
            db.bookDao().insert(book)
            assertEquals(setOf(hash), SharedLibrary.syncStore(store, db, "a".repeat(32)).hashes)
            assertEquals(2, folder.listFiles()!!.count { it.name.startsWith("bia-book-") })
            val restored = File(root, "restored.pdf")
            SharedLibrary.stageFromStore(store, book, restored)
            assertArrayEquals(file.readBytes(), restored.readBytes())
        } finally { db.close(); root.deleteRecursively() }
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
    @Test fun batchCentralEvictionVerifiesAllBeforeRemovingAnyLocalOriginal() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "batch-" + UUID.randomUUID()).apply { mkdirs() }
        val cloud = File(root, "cloud").apply { mkdirs() }
        val store = TestStore(cloud)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        fun original(id: String): BookEntity {
            val file = File(root, "books/$id/$id.pdf").apply { parentFile!!.mkdirs(); writeText(id) }
            return BookEntity(id, id, null, BookFormat.PDF, file.name, file.absolutePath,
                SharedDocumentStore.hash(file.readBytes()), file.length(), 1L)
        }
        try {
            val books = listOf(original("one"), original("two"))
            books.forEach { db.bookDao().insert(it) }
            assertTrue(SharedLibrary.syncStore(store, db, "a".repeat(32), root).errors.isEmpty())
            val missing = cloud.listFiles()!!.first { it.name.startsWith("bia-book-" + books.last().contentHash) }
            assertTrue(missing.delete())
            try {
                SharedLibrary.evictVerifiedBatch(store, db, root, books)
                fail("Missing remote original must block all eviction")
            } catch (_: IllegalArgumentException) {}
            assertTrue(books.all { File(it.localPath).isFile })
            assertTrue(SharedLibrary.syncStore(store, db, "a".repeat(32), root).errors.isEmpty())
            SharedLibrary.evictVerifiedBatch(store, db, root, books)
            for (book in books) {
                assertFalse(File(book.localPath).exists())
                assertTrue(db.bookDao().findById(book.id)!!.localPath.isBlank())
                val recovered = File(root, "recovered-" + book.id)
                SharedLibrary.stageFromStore(store, book, recovered)
                assertEquals(book.contentHash, SharedDocumentStore.hash(recovered.readBytes()))
            }
        } finally { db.close(); root.deleteRecursively() }
    }
}
