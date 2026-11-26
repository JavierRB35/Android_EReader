package com.javierrb35.bibliotecaia.sync

import android.net.Uri
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.data.*
import com.javierrb35.bibliotecaia.importer.BatchImport
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
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
    private class TestStore(private val dir: File, override val namedOriginals: Boolean = false) : SharedStore {
        private val metadata get() = File(dir, "_Sincronizacion")
        override fun list() = (dir.listFiles().orEmpty().toList() +
            if (namedOriginals) metadata.listFiles().orEmpty().toList() else emptyList()).filter { it.isFile }.map {
            SharedDocumentStore.Item(it.name, Uri.fromFile(it), it.length())
        }
        override fun open(item: SharedDocumentStore.Item): InputStream = File(item.uri.path!!).inputStream()
        override fun retireManagedState(item: SharedDocumentStore.Item, owner: String): Boolean {
            val state = SharedStateRetention.parse(item.name) ?: return false
            val file = File(item.uri.path!!)
            return namedOriginals && state.owner == owner && file.parentFile == metadata &&
                file.name == item.name && SharedDocumentStore.hash(file.readBytes()) == state.hash && file.delete()
        }
        override fun create(name: String, mime: String, source: InputStream, hash: String, size: Long): SharedDocumentStore.Item {
            val target = if (namedOriginals && (name.startsWith("bia-state-") || SharedCoverArt.isName(name))) metadata.apply {
                check(isDirectory || mkdirs())
            } else dir
            val file = File(target, name)
            check(file.createNewFile())
            source.use { input -> file.outputStream().use { input.copyTo(it) } }
            val item = SharedDocumentStore.Item(name, Uri.fromFile(file), file.length())
            check(verify(item, hash, size))
            return item
        }
    }
    @Test fun largeNamedCatalogueReusesInventoryAndAuditsEachOriginalOnce() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "setup-progress-" + UUID.randomUUID()).apply { mkdirs() }
        val folder = File(root, "cloud").apply { mkdirs() }
        val base = TestStore(folder, namedOriginals = true)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        var lists = 0
        var originalReads = 0
        val store = object : SharedStore by base {
            override fun list(): List<SharedDocumentStore.Item> {
                lists++
                return base.list()
            }
            override fun verify(item: SharedDocumentStore.Item, hash: String, size: Long): Boolean {
                if (item.name.endsWith(".epub")) originalReads++
                return base.verify(item, hash, size)
            }
        }
        val progress = mutableListOf<String>()
        try {
            val books = (1..40).map { n ->
                val bytes = "original $n ".repeat(n).toByteArray()
                val file = File(folder, "original-$n.epub").apply { writeBytes(bytes) }
                BookEntity("book-$n", "Libro $n", null, BookFormat.EPUB, file.name,
                    "", SharedDocumentStore.hash(bytes), file.length(), n.toLong())
            }
            books.forEach { db.bookDao().insert(it) }
            val result = SharedLibrary.syncStore(store, db, "a".repeat(32), onProgress = { progress.add(it) })
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            assertEquals(40, result.hashes.size)
            assertTrue("Repeated remote listings: $lists", lists <= 4)
            assertEquals(40, originalReads)
            assertTrue(progress.contains("Originales procesados: 40/40 · 40 confirmados"))
            assertTrue(progress.contains("Publicando el catálogo de 40 libros…"))
            val before = originalReads
            SharedLibrary.verifyNamedCatalogue(store, books) { progress.add(it) }
            assertEquals(40, originalReads - before)
            assertEquals("Originales verificados: 40/40", progress.last())
            // Never cache an earlier success across the independent final audit.
            val broken = File(folder, books.last().fileName)
            broken.writeBytes(ByteArray(broken.length().toInt()) { 0 })
            var rejected = false
            try { SharedLibrary.verifyNamedCatalogue(store, books) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertTrue("Same-size corruption must block setup", rejected)
            assertEquals(40, folder.listFiles()!!.count { it.name.endsWith(".epub") })
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun routineMetadataAndDownloadAvoidRepeatedOriginalReads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "metadata-fast-" + UUID.randomUUID()).apply { mkdirs() }
        val cloud = File(root, "cloud").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val base = TestStore(cloud, namedOriginals = true)
        val file = File(cloud, "fixture.epub").apply { writeText("Verified original") }
        val book = BookEntity("test-book", "Otro título", "Autor", BookFormat.EPUB, file.name,
            "", SharedDocumentStore.hash(file.readBytes()), file.length(), 1L)
        var reads = 0
        val store = object : SharedStore by base {
            override fun open(item: SharedDocumentStore.Item): InputStream {
                if (item.name == file.name) reads++
                return base.open(item)
            }
            override fun verify(item: SharedDocumentStore.Item, hash: String, size: Long): Boolean =
                open(item).use { SharedDocumentStore.digest(it, size) } == (hash to size)
            override fun extract(item: SharedDocumentStore.Item, target: File, hash: String, size: Long) {
                open(item).use { input -> target.outputStream().use { input.copyTo(it) } }
                require(target.length() == size && SharedDocumentStore.hash(target.readBytes()) == hash)
            }
        }
        try {
            db.bookDao().insert(book)
            assertEquals(setOf(book.contentHash), SharedLibrary.syncStore(store, db, "a".repeat(32)).hashes)
            reads = 0
            db.readingDao().savePosition(ReadingPositionEntity(book.id, "chapter:1", .1f, 10L))
            val result = SharedLibrary.syncStore(store, db, "a".repeat(32), verifyOriginals = false)
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            assertEquals(setOf(book.contentHash), result.hashes)
            assertEquals("Routine metadata sync must not open original bytes", 0, reads)
            val target = File(root, "download.epub")
            SharedLibrary.stageFromStore(store, book, target)
            assertEquals(1, reads)
            assertEquals(book.contentHash, SharedDocumentStore.hash(target.readBytes()))
            file.writeBytes(ByteArray(file.length().toInt()))
            var rejected = false
            try { SharedLibrary.stageFromStore(store, book, target) }
            catch (_: java.io.IOException) { rejected = true }
            assertTrue("Same-size corruption must be rejected", rejected)
            assertFalse(target.exists())
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun downloadSelectionCopiesBothFormatsOnceAndRetainsSuccessfulBooksOnFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "download-batch-" + UUID.randomUUID()).apply { mkdirs() }
        val cloud = File(root, "cloud").apply { mkdirs() }
        val local = File(root, "local").apply { mkdirs() }
        val cache = File(root, "cache").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val base = TestStore(cloud, namedOriginals = true)
        val books = listOf(BookFormat.EPUB, BookFormat.MOBI, BookFormat.EPUB).mapIndexed { i, format ->
            val file = File(cloud, "book-$i.${format.name.lowercase()}").apply { writeText("Original $i") }
            BookEntity("book-$i", "Libro $i", "Autor", format, file.name, "",
                SharedDocumentStore.hash(file.readBytes()), file.length(), i.toLong())
        }
        var lists = 0
        var reads = 0
        val store = object : SharedStore by base {
            override fun list(): List<SharedDocumentStore.Item> { lists++; return base.list() }
            override fun extract(item: SharedDocumentStore.Item, file: File, hash: String, size: Long) {
                reads++
                base.extract(item, file, hash, size)
            }
        }
        try {
            books.forEach { db.bookDao().insert(it) }
            assertEquals(3, SharedLibrary.syncStore(store, db, "a".repeat(32)).hashes.size)
            val third = File(cloud, books.last().fileName)
            third.writeBytes(ByteArray(third.length().toInt()))
            lists = 0; reads = 0
            val result = SharedLibrary.downloadBatchStore(store, db, books.map { it.contentHash }, local, cache)
            assertEquals(2, result.completed)
            assertEquals(1, result.failures.size)
            assertEquals(1, lists)
            assertEquals(3, reads)
            for (book in books.take(2)) {
                val downloaded = requireNotNull(db.bookDao().findById(book.id))
                assertEquals(book.contentHash, SharedDocumentStore.hash(File(downloaded.localPath).readBytes()))
            }
            assertTrue(db.bookDao().findById(books.last().id)!!.localPath.isBlank())
            assertTrue(cache.listFiles()!!.isEmpty())
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun importingOneBookDoesNotReadExistingOriginals() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "import-incremental-" + UUID.randomUUID()).apply { mkdirs() }
        val cloud = File(root, "cloud").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val base = TestStore(cloud, namedOriginals = true)
        val reads = mutableListOf<String>()
        val store = object : SharedStore by base {
            override fun verify(item: SharedDocumentStore.Item, hash: String, size: Long): Boolean {
                reads.add(item.name)
                return base.verify(item, hash, size)
            }
        }
        try {
            val existing = (1..40).map { index ->
                val file = File(cloud, "old-$index.epub").apply { writeText("Old original $index") }
                BookEntity("old-$index", "Libro $index", "Autor", BookFormat.EPUB, file.name, "",
                    SharedDocumentStore.hash(file.readBytes()), file.length(), index.toLong())
            }
            existing.forEach { db.bookDao().insert(it) }
            assertEquals(40, SharedLibrary.syncStore(store, db, "a".repeat(32), root).hashes.size)
            val new = File(root, "books/new/new.epub").apply { parentFile!!.mkdirs(); writeText("New original") }
            val book = BookEntity("new", "Nuevo", "Autor", BookFormat.EPUB, new.name, new.absolutePath,
                SharedDocumentStore.hash(new.readBytes()), new.length(), 41L)
            db.bookDao().insert(book)
            reads.clear()
            val result = SharedLibrary.syncStore(store, db, "a".repeat(32), root,
                verifyOriginals = false, requiredHashes = setOf(book.contentHash))
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            assertEquals(41, result.hashes.size)
            assertFalse(reads.any { it.startsWith("old-") })
            SharedLibrary.evictVerifiedBatch(store, db, root, listOf(book))
            assertFalse(reads.any { it.startsWith("old-") })
            assertFalse(new.exists())
            assertTrue(db.bookDao().findById(book.id)!!.localPath.isBlank())
            assertEquals(book.contentHash, SharedDocumentStore.hash(File(cloud, book.fileName).readBytes()))
        } finally { db.close(); root.deleteRecursively() }
    }

    @Test fun restoreZipPublishesIntoDriveWithoutInstallingLocalOriginals() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "restore-drive-" + UUID.randomUUID()).apply { mkdirs() }
        val folder = File(root, "cloud").apply { mkdirs() }
        val stage = File(root, "stage").apply { mkdirs() }
        val files = File(root, "private").apply { mkdirs() }
        val store = TestStore(folder, namedOriginals = true)
        val source = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val a = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val originals = (1..15).map { n ->
            val file = File(root, "restore-$n.epub").apply { writeText("Original $n") }
            BookEntity(UUID.randomUUID().toString(), "Libro $n", null, BookFormat.EPUB,
                file.name, file.absolutePath, SharedDocumentStore.hash(file.readBytes()), file.length(), n.toLong())
        }
        try {
            originals.forEach { source.bookDao().insert(it) }
            val first = originals.first()
            source.readingDao().savePosition(ReadingPositionEntity(first.id, "chapter:2", .2f, 10L))
            val json = com.javierrb35.bibliotecaia.backup.BackupManager(source, "test").exportJson()
            val zip = java.io.ByteArrayOutputStream()
            com.javierrb35.bibliotecaia.backup.CompleteBackupArchive.write(zip, json, originals)
            val content = com.javierrb35.bibliotecaia.backup.CompleteBackupArchive.read(zip.toByteArray().inputStream(), stage)
            val local = File(files, "books/${first.id}/${first.fileName}").apply {
                parentFile!!.mkdirs()
                writeBytes(File(first.localPath).readBytes())
            }
            a.bookDao().insert(first.copy(localPath = local.absolutePath))
            val result = SharedLibrary.migrateZipStore(store, a, "a".repeat(32), content, files)
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            assertEquals(15, result.hashes.size)
            assertEquals(15, a.bookDao().count())
            assertEquals(1, a.backupDao().booksAll().count { it.localPath.isNotBlank() })
            assertEquals(local.absolutePath, a.bookDao().findById(first.id)?.localPath)
            assertTrue(local.isFile)
            assertEquals(15, folder.listFiles()!!.count { it.name.endsWith(".epub") })
            val retry = SharedLibrary.migrateZipStore(store, a, "a".repeat(32), content, files)
            assertEquals(result.hashes, retry.hashes)
            assertEquals(15, folder.listFiles()!!.count { it.name.endsWith(".epub") })
            val remote = SharedLibrary.readCatalogueStore(store, b)
            assertEquals(result.hashes, remote.hashes)
            assertTrue(b.backupDao().booksAll().all { it.localPath.isBlank() })
            assertEquals("chapter:2", b.readingDao().position(first.id)?.locator)
        } finally { source.close(); a.close(); b.close(); root.deleteRecursively() }
    }

    @Test fun sharedThumbnailIsReusableWithoutOpeningOriginalAndRejectsCorruption() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "cover-share-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val store = TestStore(root, namedOriginals = true)
            val hash = "a".repeat(64)
            val bmp = android.graphics.Bitmap.createBitmap(20, 30, android.graphics.Bitmap.Config.ARGB_8888)
            val bytes = try { requireNotNull(com.javierrb35.bibliotecaia.ui.BookCoverLoader.jpeg(bmp)) }
                finally { bmp.recycle() }
            assertTrue(SharedCoverArt.publish(store, hash, bytes))
            assertFalse(SharedCoverArt.publish(store, hash, bytes))
            val other = TestStore(root, namedOriginals = true)
            assertArrayEquals(bytes, SharedCoverArt.read(other, other.list(), hash))
            assertNull(SharedCoverArt.read(other, other.list(), "b".repeat(64)))
            assertEquals(1, File(root, "_Sincronizacion").listFiles()!!.size)
            val image = File(root, "_Sincronizacion").listFiles()!!.single()
            image.writeText("corrupted")
            assertNull(SharedCoverArt.read(other, other.list(), hash))
        } finally { root.deleteRecursively() }
    }

    @Test fun localCoverCanBeLoadedAfterRemoteOnlyBookWithoutRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "cover-download-" + UUID.randomUUID()).apply { mkdirs() }
        try {
            val original = File(root, "book.epub")
            val bitmap = android.graphics.Bitmap.createBitmap(20, 30, android.graphics.Bitmap.Config.ARGB_8888)
            val image = try { requireNotNull(com.javierrb35.bibliotecaia.ui.BookCoverLoader.jpeg(bitmap)) }
                finally { bitmap.recycle() }
            java.util.zip.ZipOutputStream(original.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("META-INF/container.xml", """<container><rootfiles><rootfile full-path="book.opf"/></rootfiles></container>""".toByteArray())
                entry("book.opf", """<package><manifest><item id="cover" properties="cover-image" href="cover.jpg" media-type="image/jpeg"/></manifest></package>""".toByteArray())
                entry("cover.jpg", image)
            }
            val hash = SharedDocumentStore.hash(original.readBytes())
            val remote = BookEntity("cover", "Libro", null, BookFormat.EPUB, original.name, "",
                hash, original.length(), 1L)
            val loader = com.javierrb35.bibliotecaia.ui.BookCoverLoader
            assertNull(loader.load(context, remote))
            assertNotNull(loader.load(context, remote.copy(localPath = original.absolutePath)))
            // Clear RAM and remove the original: a persisted derived cache remains usable.
            loader.clearMemoryCache()
            assertTrue(original.delete())
            assertNotNull(loader.load(context, remote))
            File(context.cacheDir, "book-covers-v1/$hash.jpg").delete()
            loader.clearMemoryCache()
        } finally { root.deleteRecursively() }
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
            assertEquals(item, SharedNamedOriginals.ensure(store, a.copy(fileName = "../escape.epub"), first))
            assertThrows(IllegalArgumentException::class.java) {
                SharedNamedOriginals.ensure(store, a, other)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun duplicateDisplayNamesResolveByContentOnBothDevices() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "collision-" + UUID.randomUUID()).apply { mkdirs() }
        val cloud = File(root, "cloud").apply { mkdirs() }
        val first = File(root, "first.epub").apply { writeText("AAAA") }
        val second = File(root, "second.epub").apply { writeText("BBBB") }
        val base = TestStore(cloud, namedOriginals = true)
        val store = object : SharedStore by base {
            override fun list() = base.list() + listOf(first, second).map {
                SharedDocumentStore.Item("Same.epub", Uri.fromFile(it), it.length())
            }
        }
        val a = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val books = listOf(first, second).mapIndexed { index, file ->
                BookEntity("collision-$index", "Libro $index", null, BookFormat.EPUB,
                    "Same.epub", file.absolutePath, SharedDocumentStore.hash(file.readBytes()), file.length(), 1L)
            }
            books.forEach { a.bookDao().insert(it) }
            val hashes = books.map { it.contentHash }.toSet()
            assertEquals(hashes, SharedLibrary.syncStore(store, a, "a".repeat(32)).hashes)
            assertEquals(hashes, SharedLibrary.syncStore(store, b, "b".repeat(32)).hashes)
            for (book in books) {
                val remote = b.bookDao().findByHash(book.contentHash)!!
                val target = File(root, "restored-" + book.id)
                SharedLibrary.stageFromStore(store, remote, target)
                assertArrayEquals(File(book.localPath).readBytes(), target.readBytes())
            }
            assertTrue(cloud.listFiles()!!.none { it.isFile })
            assertEquals("AAAA", first.readText())
            assertEquals("BBBB", second.readText())
        } finally { a.close(); b.close(); root.deleteRecursively() }
    }

    @Test fun browseCatalogueDoesNotOpenOriginalStreams() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "fast-catalogue-" + UUID.randomUUID()).apply { mkdirs() }
        val cloud = File(root, "cloud").apply { mkdirs() }
        val a = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val original = File(root, "book.epub").apply { writeText("original bytes") }
            val hash = SharedDocumentStore.hash(original.readBytes())
            val book = BookEntity("browse", "Libro", null, BookFormat.EPUB, original.name, original.absolutePath,
                hash, original.length(), 1L)
            a.bookDao().insert(book)
            val base = TestStore(cloud, namedOriginals = true)
            assertTrue(SharedLibrary.syncStore(base, a, "a".repeat(32), root).errors.isEmpty())
            var opens = 0
            val counted = object : SharedStore by base {
                override fun open(item: SharedDocumentStore.Item): InputStream {
                    if (item.name.endsWith(".epub")) opens++
                    return base.open(item)
                }
                override fun verify(item: SharedDocumentStore.Item, hash: String, size: Long): Boolean {
                    if (item.name.endsWith(".epub")) opens++
                    return base.verify(item, hash, size)
                }
            }
            val result = SharedLibrary.readCatalogueStore(counted, b, root)
            assertTrue(result.errors.isEmpty())
            assertTrue(hash in result.hashes)
            assertEquals(0, opens)
            assertNotNull(b.bookDao().findByHash(hash))
            assertTrue(hash in SharedLibrary.pullStore(counted, b, root).hashes)
            assertTrue(opens > 0)
            // Same-size corruption may remain a browse candidate but must fail verification.
            File(cloud, original.name).writeBytes(ByteArray(original.length().toInt()) { 7 })
            assertTrue(hash in SharedLibrary.readCatalogueStore(counted, b, root).hashes)
            assertFalse(hash in SharedLibrary.pullStore(counted, b, root).hashes)
        } finally { a.close(); b.close(); root.deleteRecursively() }
    }

    @Test fun namedFolderTwoDevicesShareReadableOriginalsAndReadingPosition() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "named-sync-" + UUID.randomUUID()).apply { mkdirs() }
        val folder = File(root, "cloud").apply { mkdirs() }
        val a = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File(root, "Mi libro.epub").apply { writeText("original contents") }
        val hash = SharedDocumentStore.hash(file.readBytes())
        val book = BookEntity("a", "Libro", null, BookFormat.EPUB, file.name,
            file.absolutePath, hash, file.length(), 1L)
        val store = TestStore(folder, namedOriginals = true)
        try {
            a.bookDao().insert(book)
            a.readingDao().savePosition(ReadingPositionEntity(book.id, "page:8", .4f, 100L))
            assertTrue(SharedLibrary.syncStore(store, a, "a".repeat(32)).errors.isEmpty())
            assertArrayEquals(file.readBytes(), File(folder, file.name).readBytes())
            assertEquals(listOf(file.name), folder.listFiles()!!.filter { it.isFile }.map { it.name })
            assertTrue(File(folder, "_Sincronizacion").listFiles()!!.any { it.name.endsWith(".json") })
            assertEquals(setOf(hash), SharedLibrary.syncStore(store, b, "b".repeat(32)).hashes)
            val remote = b.bookDao().findByHash(hash)!!
            val staged = File(root, "copy.epub")
            SharedLibrary.stageFromStore(store, remote, staged)
            assertArrayEquals(file.readBytes(), staged.readBytes())
            b.readingDao().savePosition(ReadingPositionEntity(remote.id, "page:3", .1f, 200L))
            assertTrue(SharedLibrary.syncStore(store, b, "b".repeat(32)).errors.isEmpty())
            assertTrue(SharedLibrary.syncStore(store, a, "a".repeat(32)).errors.isEmpty())
            assertEquals("page:3", a.readingDao().position(book.id)!!.locator)
            assertEquals(1, folder.listFiles()!!.count { it.isFile })
        } finally { a.close(); b.close(); root.deleteRecursively() }
    }

    @Test fun repeatedReadingStatesAreBoundedAndRecoverAcrossDevices() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "bounded-state-" + UUID.randomUUID()).apply { mkdirs() }
        val folder = File(root, "cloud").apply { mkdirs() }
        val a = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val recovered = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File(root, "Libro.epub").apply { writeText("original contents") }
        val hash = SharedDocumentStore.hash(file.readBytes())
        val book = BookEntity("bounded-a", "Libro", null, BookFormat.EPUB, file.name,
            file.absolutePath, hash, file.length(), 1L)
        val ownerA = "a".repeat(32)
        val ownerB = "b".repeat(32)
        val store = TestStore(folder, namedOriginals = true)
        fun states(owner: String) = store.list().filter { SharedStateRetention.parse(it.name)?.owner == owner }
        try {
            a.bookDao().insert(book)
            a.readingDao().savePosition(ReadingPositionEntity(book.id, "page:0", 0f, 100L))
            assertTrue(SharedLibrary.syncStore(store, a, ownerA).errors.isEmpty())
            // Simulate an existing pre-upgrade immutable publication.
            val old = File(states(ownerA).single().uri.path!!)
            val nonceStart = old.name.length - 5 - 36
            val legacy = File(old.parentFile, old.name.replaceRange(nonceStart + 14, nonceStart + 15, "4"))
            assertTrue(old.renameTo(legacy))
            assertTrue(SharedLibrary.syncStore(store, b, ownerB).errors.isEmpty())
            val peer = b.bookDao().findByHash(hash)!!
            val note = AnnotationEntity("bounded-note", peer.id, "page:0", "quote", "note from B", "NOTE", 0, 1L, 101L)
            b.readingDao().insertAnnotation(note)
            assertTrue(SharedLibrary.syncStore(store, b, ownerB).errors.isEmpty())
            val peerFiles = states(ownerB).map { it.name }.toSet()
            for (i in 1..12) {
                a.readingDao().savePosition(ReadingPositionEntity(book.id, "page:$i", i / 20f, 100L + i))
                assertTrue(SharedLibrary.syncStore(store, a, ownerA).errors.isEmpty())
                assertTrue(states(ownerA).size <= SharedStateRetention.KEEP)
            }
            assertTrue(legacy.isFile)
            assertEquals(peerFiles, states(ownerB).map { it.name }.toSet())
            assertEquals(3, states(ownerA).size)
            assertEquals("note from B", a.readingDao().annotation(note.id)!!.body)
            assertTrue(SharedLibrary.syncStore(store, b, ownerB).errors.isEmpty())
            assertEquals("page:12", b.readingDao().position(peer.id)!!.locator)
            b.readingDao().savePosition(ReadingPositionEntity(peer.id, "page:2", .1f, 1000L))
            assertTrue(SharedLibrary.syncStore(store, b, ownerB).errors.isEmpty())
            assertTrue(SharedLibrary.syncStore(store, a, ownerA).errors.isEmpty())
            assertEquals("page:2", a.readingDao().position(book.id)!!.locator)
            // B stays offline; only A has these two newer positions.
            a.readingDao().savePosition(ReadingPositionEntity(book.id, "page:3", .15f, 1001L))
            assertTrue(SharedLibrary.syncStore(store, a, ownerA).errors.isEmpty())
            a.readingDao().savePosition(ReadingPositionEntity(book.id, "page:4", .2f, 1002L))
            assertTrue(SharedLibrary.syncStore(store, a, ownerA).errors.isEmpty())
            val newest = states(ownerA).maxByOrNull { SharedStateRetention.parse(it.name)!!.sequence }!!
            File(newest.uri.path!!).writeText("interrupted publication")
            val before = states(ownerA).map { it.name }.toSet()
            SharedLibrary.pullStore(store, recovered, root)
            val recoveredBook = requireNotNull(recovered.bookDao().findByHash(hash))
            assertEquals("page:3", recovered.readingDao().position(recoveredBook.id)!!.locator)
            assertEquals("note from B", recovered.readingDao().annotation(note.id)!!.body)
            assertEquals(before, states(ownerA).map { it.name }.toSet())
            assertTrue(legacy.isFile)
            assertArrayEquals(file.readBytes(), File(folder, file.name).readBytes())
        } finally { a.close(); b.close(); recovered.close(); root.deleteRecursively() }
    }

    @Test fun simultaneousWritersConvergeAndNeverRetirePeerState() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "concurrent-states-" + UUID.randomUUID()).apply { mkdirs() }
        val cloud = File(root, "cloud").apply { mkdirs() }
        val a = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val b = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val file = File(root, "Concurrent.epub").apply { writeText("original contents") }
        val hash = SharedDocumentStore.hash(file.readBytes())
        val book = BookEntity("concurrent-a", "Libro", null, BookFormat.EPUB, file.name,
            file.absolutePath, hash, file.length(), 1L)
        val ownerA = "a".repeat(32)
        val ownerB = "b".repeat(32)
        val base = TestStore(cloud, namedOriginals = true)
        fun states(owner: String) = base.list().filter { SharedStateRetention.parse(it.name)?.owner == owner }
        try {
            a.bookDao().insert(book)
            assertTrue(SharedLibrary.syncStore(base, a, ownerA).errors.isEmpty())
            assertTrue(SharedLibrary.syncStore(base, b, ownerB).errors.isEmpty())
            val peer = b.bookDao().findByHash(hash)!!
            // Fill the retention window before making two independent offline edits.
            for (i in 1..4) {
                a.readingDao().savePosition(ReadingPositionEntity(book.id, "page:$i", i / 20f, 100L + i * 2))
                assertTrue(SharedLibrary.syncStore(base, a, ownerA).errors.isEmpty())
                b.readingDao().savePosition(ReadingPositionEntity(peer.id, "page:$i", i / 20f, 101L + i * 2))
                assertTrue(SharedLibrary.syncStore(base, b, ownerB).errors.isEmpty())
            }
            val noteA = AnnotationEntity("concurrent-note-a", book.id, "page:5", "a", "note A", "NOTE", 0, 1L, 500L)
            val noteB = AnnotationEntity("concurrent-note-b", peer.id, "page:2", "b", "note B", "NOTE", 0, 1L, 501L)
            a.readingDao().insertAnnotation(noteA)
            b.readingDao().insertAnnotation(noteB)
            a.readingDao().savePosition(ReadingPositionEntity(book.id, "page:5", .25f, 500L))
            b.readingDao().savePosition(ReadingPositionEntity(peer.id, "page:2", .1f, 501L))
            val barrier = CyclicBarrier(2)
            fun concurrentStore(owner: String) = object : SharedStore by base {
                override fun create(name: String, mime: String, source: InputStream, hash: String, size: Long): SharedDocumentStore.Item {
                    if (SharedStateRetention.parse(name)?.owner == owner)
                        barrier.await(15, TimeUnit.SECONDS)
                    return base.create(name, mime, source, hash, size)
                }
                override fun retireManagedState(item: SharedDocumentStore.Item, writer: String): Boolean {
                    assertEquals(owner, writer)
                    assertEquals(owner, SharedStateRetention.parse(item.name)?.owner)
                    return base.retireManagedState(item, writer)
                }
            }
            val results = listOf(
                async(Dispatchers.IO) { SharedLibrary.syncStore(concurrentStore(ownerA), a, ownerA) },
                async(Dispatchers.IO) { SharedLibrary.syncStore(concurrentStore(ownerB), b, ownerB) }
            ).awaitAll()
            assertTrue(results.all { it.errors.isEmpty() })
            assertEquals(3, states(ownerA).size)
            assertEquals(3, states(ownerB).size)
            assertTrue(SharedLibrary.syncStore(base, a, ownerA).errors.isEmpty())
            assertTrue(SharedLibrary.syncStore(base, b, ownerB).errors.isEmpty())
            for (db in listOf(a, b)) {
                assertEquals("note A", db.readingDao().annotation(noteA.id)!!.body)
                assertEquals("note B", db.readingDao().annotation(noteB.id)!!.body)
                val id = db.bookDao().findByHash(hash)!!.id
                assertEquals("page:2", db.readingDao().position(id)!!.locator)
            }
            assertEquals(3, states(ownerA).size)
            assertEquals(3, states(ownerB).size)
            assertArrayEquals(file.readBytes(), File(cloud, file.name).readBytes())
            // Provider refuses retirement: publish remains recoverable, and no
            // candidate is deleted. A later successful retry cleans the surplus.
            val noDelete = object : SharedStore by base {
                override fun retireManagedState(item: SharedDocumentStore.Item, owner: String) = false
            }
            a.readingDao().savePosition(ReadingPositionEntity(book.id, "page:6", .3f, 600L))
            assertTrue(SharedLibrary.syncStore(noDelete, a, ownerA).errors.any { it.contains("Compactación pendiente") })
            assertEquals(4, states(ownerA).size)
            assertTrue(SharedLibrary.syncStore(base, a, ownerA).errors.isEmpty())
            assertEquals(3, states(ownerA).size)
            assertTrue(SharedLibrary.syncStore(base, b, ownerB).errors.isEmpty())
            assertEquals("page:6", b.readingDao().position(peer.id)!!.locator)
        } finally { a.close(); b.close(); root.deleteRecursively() }
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
    @Test fun centralImportWithoutBackupRetainsOfflineOriginalAndReconcilesOnSecondDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "import-no-backup-" + UUID.randomUUID()).apply { mkdirs() }
        val cloud = File(root, "cloud").apply { mkdirs() }
        val store = TestStore(cloud, namedOriginals = true)
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val receiver = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val original = File(root, "books/new/Mi libro.epub").apply {
            parentFile!!.mkdirs()
            writeText("new original")
        }
        val hash = SharedDocumentStore.hash(original.readBytes())
        val book = BookEntity("new", "Libro", null, BookFormat.EPUB, original.name,
            original.absolutePath, hash, original.length(), 1L)
        try {
            assertEquals(SharedWriteAccess.READY, SharedWriteAccess.evaluate(true, true, false))
            val result = BatchImport.run(listOf(book), true,
                importOne = { db.bookDao().insert(it); it },
                publishVerified = { throw java.io.IOException("Sin conexión") },
                removeStaging = { fail("No retirar originales sin verificación") })
            assertEquals("Sin conexión", result.centralError)
            assertTrue(original.isFile)
            assertNotNull(db.bookDao().findByHash(hash))
            // Retry from persisted Room and local bytes; no backup or ZIP configured.
            assertTrue(SharedLibrary.syncStore(store, db, "a".repeat(32), root).errors.isEmpty())
            assertEquals(setOf(hash), SharedLibrary.syncStore(store, receiver, "b".repeat(32)).hashes)
            val copy = File(root, "remote.epub")
            SharedLibrary.stageFromStore(store, receiver.bookDao().findByHash(hash)!!, copy)
            assertArrayEquals(original.readBytes(), copy.readBytes())
            SharedLibrary.evictVerifiedBatch(store, db, root, listOf(book))
            assertFalse(original.exists())
            assertEquals(listOf(book.fileName), cloud.listFiles()!!.filter { it.isFile }.map { it.name })
        } finally { db.close(); receiver.close(); root.deleteRecursively() }
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
