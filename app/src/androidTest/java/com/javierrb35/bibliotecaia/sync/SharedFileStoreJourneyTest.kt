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
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/** Production file-only transport, using local document streams rather than
 * pretending that a simulated provider confirms actual Drive propagation. */
@RunWith(AndroidJUnit4::class)
class SharedFileStoreJourneyTest {
    private class Access : SharedContainerAccess {
        var failNextWrite = false
        val writes = mutableListOf<Uri>()
        override fun requireGrant(uri: Uri, write: Boolean) {}
        override fun input(uri: Uri) = File(uri.path!!).inputStream()
        override fun output(uri: Uri): OutputStream {
            writes.add(uri)
            val file = File(uri.path!!)
            val output = file.outputStream()
            if (!failNextWrite) return output
            failNextWrite = false
            return object : OutputStream() {
                override fun write(b: Int) {
                    output.write(b)
                    throw IOException("simulated provider failure")
                }
                override fun close() = output.close()
            }
        }
    }
    @Test fun connectionHistoryPreservesModeAndPeerPermissions() {
        val file = SharedLibrary.SavedConnection(Uri.parse("content://provider/document/owner"),
            true, setOf("content://provider/document/peer-a", "content://provider/document/peer-b"))
        val folder = SharedLibrary.SavedConnection(Uri.parse("content://provider/tree/root"), false, emptySet())
        assertEquals(file, SharedLibrary.SavedConnection.decode(file.encode()))
        assertEquals(folder, SharedLibrary.SavedConnection.decode(folder.encode()))
        assertNotEquals(file, folder)
    }
    @Test fun fileOnlyDevicesNeverOverwritePeersAndRecoverInterruptedWrite() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "file-journey-" + UUID.randomUUID()).apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val filesA = File(root, "a-files").apply { mkdirs() }
        val filesB = File(root, "b-files").apply { mkdirs() }
        val uriA = Uri.fromFile(File(root, "a.zip").apply { createNewFile() })
        val uriB = Uri.fromFile(File(root, "b.zip").apply { createNewFile() })
        val ownerA = "a".repeat(32)
        val ownerB = "b".repeat(32)
        val access = Access()
        val a = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        fun openA() = SharedFileStore(cache, filesA, access, uriA, listOf(uriB), ownerA)
        fun openB() = SharedFileStore(cache, filesB, access, uriB, listOf(uriA), ownerB)
        try {
            SharedFileStore(cache, filesA, access, uriA, emptyList(), ownerA).use { it.commit() }
            SharedFileStore(cache, filesB, access, uriB, emptyList(), ownerB).use { it.commit() }
            val original = File(root, "first.epub").apply { writeText("original verified bytes") }
            val book = BookEntity("one", "Uno", null, BookFormat.EPUB, original.name,
                original.absolutePath, SharedDocumentStore.hash(original.readBytes()), original.length(), 1L)
            a.bookDao().insert(book)
            openA().use { assertTrue(SharedLibrary.syncStore(it, a, ownerA).errors.isEmpty()) }
            val beforeA = File(uriA.path!!).readBytes()
            openB().use { assertTrue(SharedLibrary.syncStore(it, b, ownerB).errors.isEmpty()) }
            assertArrayEquals(beforeA, File(uriA.path!!).readBytes())
            val peer = b.bookDao().findByHash(book.contentHash)!!
            assertTrue(peer.localPath.isBlank())
            val recovered = File(root, "download.epub")
            openB().use { SharedLibrary.stageFromStore(it, peer, recovered) }
            assertArrayEquals(original.readBytes(), recovered.readBytes())
            a.readingDao().savePosition(ReadingPositionEntity(book.id, "page:3", .3f, 10L))
            access.failNextWrite = true
            try {
                openA().use { SharedLibrary.syncStore(it, a, ownerA) }
                fail("Write failure must not report success")
            } catch (_: IOException) {}
            // The next open uses the local rollback, not another device's file.
            openA().use { assertArrayEquals(beforeA, File(uriA.path!!).readBytes()) }
            openA().use { assertTrue(SharedLibrary.syncStore(it, a, ownerA).errors.isEmpty()) }
            openB().use { assertTrue(SharedLibrary.syncStore(it, b, ownerB).errors.isEmpty()) }
            assertEquals(.3f, b.readingDao().position(peer.id)!!.progress, 0f)
            try {
                SharedFileStore(cache, filesB, access, uriA, emptyList(), ownerB).use { it.commit() }
                fail("Must refuse another device's archive as own")
            } catch (_: IllegalArgumentException) {}
        } finally { a.close(); b.close(); root.deleteRecursively() }
    }
}
