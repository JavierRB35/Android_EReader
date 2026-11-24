package com.javierrb35.bibliotecaia.sync

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.javierrb35.bibliotecaia.BuildConfig
import com.javierrb35.bibliotecaia.backup.*
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.importer.LocalBookCopyRemover
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Opt-in central folder. Immutable originals, immutable verified catalogue
 * publications, one local cache per device. ZIP is a separate recovery format.
 * Never trust file names as proof of content; never overwrite a peer's data.
 */
object SharedLibrary {
    private const val PREFS = "shared_library_v1"
    private const val MAX_STATE = 16 * 1024 * 1024
    private val mutex = Mutex()
    private val objectName = Regex("bia-book-([a-f0-9]{64})-[a-f0-9-]{36}\\.bin")
    private val stateName = Regex("bia-state-([a-f0-9]{32})-([0-9]{1,19})-([a-f0-9]{64})-[a-f0-9-]{36}\\.json")
    val changes = MutableStateFlow(0L)
    data class Receipt(val hash: String, val name: String, val bytes: Long)
    data class Result(val verified: Int, val errors: List<String>)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun uri(context: Context): Uri? = prefs(context).getString("tree", null)?.let(Uri::parse)
    fun configured(context: Context) = uri(context) != null
    fun hashes(context: Context): Set<String> = prefs(context).getStringSet("hashes", emptySet()).orEmpty().toSet()
    fun status(context: Context): String = prefs(context).getString("status", "Sin carpeta central configurada.").orEmpty()

    private fun store(context: Context, tree: Uri = requireNotNull(uri(context))): SharedDocumentStore {
        require(context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }) { "La carpeta central no tiene permiso persistente de lectura y escritura." }
        return SharedDocumentStore(context, tree)
    }
    private fun publishStatus(context: Context, hashes: Set<String>, errors: List<String>) {
        val message = if (errors.isEmpty()) "Biblioteca comprobada: " + hashes.size + " originales."
            else "Sincronización parcial: " + errors.joinToString(" · ").take(1200)
        prefs(context).edit().putStringSet("hashes", hashes).putString("status", message).apply()
        changes.value += 1
    }
    suspend fun connect(context: Context, db: AppDatabase, tree: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(uri(context) == null || uri(context) == tree) {
                "La biblioteca ya tiene una carpeta configurada. No se cambiará sin migración explícita."
            }
            store(context, tree).probe()
            check(prefs(context).edit().putString("tree", tree.toString()).commit())
        }
        schedule(context)
        sync(context, db)
    }

    private fun readBounded(store: SharedDocumentStore, item: SharedDocumentStore.Item): ByteArray =
        store.open(item).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                require(out.size() + n <= MAX_STATE) { "Catálogo demasiado grande." }
                if (n > 0) out.write(buf, 0, n)
            }
            out.toByteArray()
        }

    private fun receipts(state: JSONObject): List<Receipt> {
        require(state.getInt("version") == 1)
        val array = state.getJSONArray("originals")
        require(array.length() <= 10000)
        return (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            val receipt = Receipt(row.getString("hash"), row.getString("name"), row.getLong("bytes"))
            require(objectName.matchEntire(receipt.name)?.groupValues?.get(1) == receipt.hash &&
                receipt.bytes in 1L..(512L * 1024 * 1024))
            receipt
        }
    }
    private data class Inventory(val receipts: Map<String, List<Receipt>>, val items: Map<String, SharedDocumentStore.Item>,
                                 val states: List<String>, val errors: List<String>) {
        fun available(hash: String): Boolean = receipts[hash].orEmpty().any {
            val item = items[it.name]
            item != null && (item.size < 0 || item.size == it.bytes)
        }
        fun availableHashes(): Set<String> = receipts.keys.filter(::available).toSet()
    }
    private fun inventory(store: SharedDocumentStore): Inventory {
        val files = store.list()
        val items = files.groupBy { it.name }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val states = mutableListOf<String>()
        val found = mutableListOf<Receipt>()
        val errors = mutableListOf<String>()
        val perDevice = files.mapNotNull { item ->
            stateName.matchEntire(item.name)?.let { it to item }
        }.groupBy { it.first.groupValues[1] }
        // Only the latest valid publication per device is needed. Previous
        // immutable publications remain available for recovery, not re-merged.
        for (versions in perDevice.values) {
            for ((match, item) in versions.sortedByDescending { it.first.groupValues[2].toLong() }) {
            try {
                val bytes = readBounded(store, item)
                require(SharedDocumentStore.hash(bytes) == match.groupValues[3]) { "Catálogo incompleto." }
                val root = JSONObject(String(bytes, Charsets.UTF_8))
                val json = root.getString("snapshot")
                val books = BackupCodec.fromJson(json).books.associateBy { it.contentHash }
                val verified = receipts(root).filter { r ->
                    val book = books[r.hash]
                    book != null && book.sizeBytes == r.bytes
                }
                states.add(json)
                found.addAll(verified)
                break
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors.add("No se pudo leer " + item.name.take(32)) }
            }
        }
        return Inventory(found.distinct().groupBy { it.hash }, items, states, errors)
    }

    private fun upload(store: SharedDocumentStore, book: BookEntity, file: File): Receipt {
        require(file.isFile && file.length() == book.sizeBytes &&
            OriginalLinker(file.parentFile ?: File(".")).sha256Hex(file) == book.contentHash) {
            "Original local no válido: " + book.title.take(80)
        }
        val name = "bia-book-" + book.contentHash + "-" + UUID.randomUUID() + ".bin"
        file.inputStream().use { store.create(name, "application/octet-stream", it, book.contentHash, book.sizeBytes) }
        return Receipt(book.contentHash, name, book.sizeBytes)
    }
    private suspend fun publish(context: Context, store: SharedDocumentStore, db: AppDatabase, receipts: Collection<Receipt>) {
        val raw = BackupCodec.fromJson(BackupManager(db, BuildConfig.VERSION_NAME).exportJson(0))
        val canonical = raw.copy(books = raw.books.sortedBy { it.id }, categories = raw.categories.sortedBy { it.id },
            tags = raw.tags.sortedBy { it.id }, collections = raw.collections.sortedBy { it.id },
            bookCategories = raw.bookCategories.sortedWith(compareBy({ it.bookId }, { it.categoryId })),
            bookTags = raw.bookTags.sortedWith(compareBy({ it.bookId }, { it.tagId })),
            collectionBooks = raw.collectionBooks.sortedWith(compareBy({ it.bookId }, { it.collectionId })),
            annotations = raw.annotations.sortedBy { it.id }, versions = raw.versions.sortedBy { it.id },
            positions = raw.positions.sortedBy { it.bookId }, synopses = raw.synopses.sortedBy { it.bookId })
        val json = BackupCodec.toJson(canonical, BuildConfig.VERSION_NAME, 0)
        val known = BackupCodec.fromJson(json).books.map { it.contentHash }.toSet()
        val array = org.json.JSONArray()
        receipts.filter { it.hash in known }.distinct().sortedBy { it.name }.forEach {
            array.put(JSONObject().put("hash", it.hash).put("name", it.name).put("bytes", it.bytes))
        }
        val bytes = JSONObject().put("version", 1).put("snapshot", json)
            .put("originals", array).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_STATE)
        val hash = SharedDocumentStore.hash(bytes)
        val owner = ReadingPositionSafSync.deviceId(context)
        val own = store.list().mapNotNull { item ->
            stateName.matchEntire(item.name)?.takeIf { it.groupValues[1] == owner }?.let { it to item }
        }.maxByOrNull { it.first.groupValues[2].toLong() }
        if (own != null && own.first.groupValues[3] == hash &&
            runCatching { store.verify(own.second, hash, bytes.size.toLong()) }.getOrDefault(false)) return
        val seq = (own?.first?.groupValues?.get(2)?.toLong() ?: 0L) + 1L
        require(seq > 0L)
        store.create("bia-state-" + owner + "-" + seq + "-" + hash + "-" + UUID.randomUUID() + ".json",
            "application/json", bytes.inputStream(), hash, bytes.size.toLong())
    }
    suspend fun sync(context: Context, db: AppDatabase): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = store(context)
            val inv = inventory(store)
            val errors = inv.errors.toMutableList()
            val manager = BackupManager(db, BuildConfig.VERSION_NAME)
            for (json in inv.states) {
                try { manager.restoreJson(json, synchronize = true) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors.add("Catálogo no integrado: " + (e.message ?: "error")) }
            }
            val known = inv.receipts.toMutableMap()
            val available = inv.availableHashes().toMutableSet()
            for (book in db.backupDao().booksAll()) {
                if (book.contentHash in available || !CentralCatalogue.local(book)) continue
                try {
                    val receipt = upload(store, book, File(book.localPath))
                    known[book.contentHash] = known[book.contentHash].orEmpty() + receipt
                    available.add(book.contentHash)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors.add(book.title.take(80) + ": " + (e.message ?: "no se pudo subir")) }
            }
            // A failed upload does not block the other books or reading state.
            publish(context, store, db, known.values.flatten())
            publishStatus(context, available, errors)
            Result(available.size, errors)
        }
    }
    suspend fun migrateZip(context: Context, db: AppDatabase, zip: Uri): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = store(context)
            val inv = inventory(store)
            val stage = File(context.cacheDir, "shared-migrate-" + UUID.randomUUID())
            check(stage.mkdirs())
            try {
                val content = context.contentResolver.openInputStream(zip)?.use {
                    CompleteBackupArchive.read(it, stage)
                } ?: throw IOException("No se puede leer el ZIP.")
                val snapshot = BackupCodec.fromJson(content.json)
                val known = inv.receipts.toMutableMap()
                val available = inv.availableHashes().toMutableSet()
                for (book in snapshot.books) {
                    if (book.contentHash !in available) {
                        val file = content.staged.getValue(CompleteBackupArchive.entryName(book.id))
                        known[book.contentHash] = known[book.contentHash].orEmpty() + upload(store, book, file)
                        available.add(book.contentHash)
                    }
                }
                BackupManager(db, BuildConfig.VERSION_NAME).restoreJson(content.json, synchronize = true)
                publish(context, store, db, known.values.flatten())
                publishStatus(context, available, inv.errors)
                Result(available.size, inv.errors)
            } finally { stage.deleteRecursively() }
        }
    }
    private fun verifiedCopy(store: SharedDocumentStore, inv: Inventory, book: BookEntity, target: File) {
        for (receipt in inv.receipts[book.contentHash].orEmpty()) {
            val item = inv.items[receipt.name] ?: continue
            try {
                require(receipt.bytes == book.sizeBytes)
                store.extract(item, target, book.contentHash, book.sizeBytes)
                return
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { target.delete() }
        }
        throw IOException("No se encuentra un original central íntegro de " + book.title.take(80))
    }
    suspend fun download(context: Context, db: AppDatabase, hash: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = store(context)
            val inv = inventory(store)
            val manager = BackupManager(db, BuildConfig.VERSION_NAME)
            for (json in inv.states) {
                try { manager.restoreJson(json, synchronize = true) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Isolate invalid peer metadata. */ }
            }
            val book = db.bookDao().findByHash(hash) ?: error("Libro no encontrado.")
            if (CentralCatalogue.local(book) &&
                OriginalLinker(context.filesDir).sha256Hex(File(book.localPath)) == hash) return@withLock
            val file = File.createTempFile("shared-download-", ".tmp", context.cacheDir)
            try {
                verifiedCopy(store, inv, book, file)
                val linked = OriginalLinker(context.filesDir).link(file, listOf(book))
                    ?: error("No se puede colocar el original en el dispositivo.")
                db.bookDao().update(linked)
            } finally { file.delete() }
        }
    }
    suspend fun evict(context: Context, db: AppDatabase, id: String) {
        val result = sync(context, db)
        require(result.errors.isEmpty()) { "Sincronización incompleta; no se retirará el original." }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val store = store(context)
                val inv = inventory(store)
                val book = db.bookDao().findById(id) ?: error("Libro no encontrado.")
                require(inv.receipts[book.contentHash].orEmpty().any { receipt ->
                    val item = inv.items[receipt.name]
                    item != null && receipt.bytes == book.sizeBytes &&
                        runCatching { store.verify(item, book.contentHash, book.sizeBytes) }.getOrDefault(false)
                }) { "No se pudo comprobar el original central. Se conserva el archivo local." }
                LocalBookCopyRemover.delete(context.filesDir, book)
                check(db.bookDao().clearLocalPath(id, book.localPath) == 1)
            }
        }
    }
    /** Independent portable ZIP export can recover non-downloaded originals
     * from the shared folder without changing the local download state. */
    suspend fun stageOriginal(context: Context, book: BookEntity, file: File) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val store = store(context)
            verifiedCopy(store, inventory(store), book, file)
        }
    }
    suspend fun pull(context: Context, db: AppDatabase) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val inv = inventory(store(context))
            val errors = inv.errors.toMutableList()
            val manager = BackupManager(db, BuildConfig.VERSION_NAME)
            for (json in inv.states) {
                try { manager.restoreJson(json, synchronize = true) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors.add(e.message ?: "Catálogo incompatible.") }
            }
            publishStatus(context, inv.availableHashes(), errors)
        }
    }
    fun recordFailure(context: Context, error: Exception) {
        prefs(context).edit().putString("status", "Pendiente de sincronizar: " + (error.message ?: "error")).apply()
        changes.value += 1
    }
    fun schedule(context: Context) {
        if (!configured(context)) return
        val work = WorkManager.getInstance(context.applicationContext)
        work.enqueueUniqueWork("shared-library-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SharedLibraryWorker>().setInitialDelay(3, TimeUnit.SECONDS).build())
        work.enqueueUniquePeriodicWork("shared-library-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SharedLibraryWorker>(15, TimeUnit.MINUTES).build())
    }
}

class SharedLibraryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SharedLibrary.configured(applicationContext)) return Result.success()
        return try {
            val result = SharedLibrary.sync(applicationContext, AppDatabase.get(applicationContext))
            if (result.errors.isEmpty()) Result.success() else Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            SharedLibrary.recordFailure(applicationContext, e)
            Result.retry()
        }
    }
}
