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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
    fun peerCount(context: Context): Int = filePeers(context).size
    fun connectedName(context: Context): String {
        val selected = uri(context) ?: return ""
        return runCatching {
            context.contentResolver.query(selected, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: if (fileMode(context)) "" else "Carpeta seleccionada"
    }
    fun hasAccess(context: Context): Boolean {
        val selected = uri(context) ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == selected && it.isReadPermission && it.isWritePermission
        }
    }
    fun needsRepair(context: Context): Boolean {
        val status = prefs(context).getString("status", "").orEmpty()
        return fileMode(context) && (prefs(context).getBoolean("owner_mismatch", false) ||
            status.contains("El archivo pertenece a otro dispositivo") ||
            status.contains("La identidad del archivo no coincide"))
    }
    fun lastSuccess(context: Context): Long = prefs(context).getLong("last_success", 0L)
    fun lastPublication(context: Context): Long = prefs(context).getLong("last_publication", 0L)
    fun isLibraryDocument(context: Context, candidate: Uri): Boolean =
        (listOfNotNull(currentConnection(context)) + previousConnections(context)).any {
            it.uri == candidate || candidate.toString() in it.peers
        }
    fun status(context: Context): String {
        if (!configured(context)) return "Sin biblioteca compartida configurada."
        val mode = if (fileMode(context)) "Contenedor propio · ${filePeers(context).size} otros dispositivos vinculados"
            else "Carpeta compartida"
        return mode + "\n" + prefs(context).getString("status", "Pendiente de comprobar.").orEmpty()
    }

    data class SavedConnection(val uri: Uri, val files: Boolean, val peers: Set<String>) {
        val label: String get() = (if (files) "Contenedor" else "Carpeta") +
            " · " + (uri.lastPathSegment ?: uri.authority ?: "Almacenamiento").takeLast(90)
        internal fun encode() = JSONObject().put("uri", uri.toString()).put("files", files)
            .put("peers", org.json.JSONArray(peers.sorted())).toString()
        companion object {
            internal fun decode(value: String): SavedConnection {
                val obj = JSONObject(value)
                val peers = obj.getJSONArray("peers")
                return SavedConnection(Uri.parse(obj.getString("uri")), obj.getBoolean("files"),
                    (0 until peers.length()).map { peers.getString(it) }.toSet())
            }
        }
    }
    private fun currentConnection(context: Context): SavedConnection? = uri(context)?.let {
        SavedConnection(it, fileMode(context), filePeers(context).map(Uri::toString).toSet())
    }
    fun previousConnections(context: Context): List<SavedConnection> =
        prefs(context).getStringSet("previous_connections", emptySet()).orEmpty()
            .mapNotNull { runCatching { SavedConnection.decode(it) }.getOrNull() }
            .filter { it.uri != uri(context) }.sortedBy { it.label }
    // Call only under the sync mutex, after the new destination is validated.
    // Keep old grants and remote files; do not change Room or the backup URI.
    private fun activate(context: Context, connection: SavedConnection) {
        val old = currentConnection(context)
        val history = previousConnections(context).filter { it.uri != connection.uri }.toMutableList()
        if (old != null && old.uri != connection.uri) history.add(old)
        val edit = prefs(context).edit().putString("tree", connection.uri.toString())
            .putString("mode", if (connection.files) "files" else "folder")
            .putStringSet("file_peers", connection.peers)
            .putStringSet("previous_connections", history.map { it.encode() }.toSet())
            .remove("owner_mismatch")
        if (old?.uri != connection.uri) edit.remove("hashes").remove("last_success").remove("last_publication").putString("status", "Pendiente de sincronizar.")
        check(edit.commit()) { "No se pudo guardar la nueva conexión." }
        changes.value += 1
    }
    suspend fun restoreConnection(context: Context, db: AppDatabase, connection: SavedConnection) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(connection in previousConnections(context)) { "Conexión anterior no disponible." }
            if (connection.files) {
                SharedFileStore(context, connection.uri, connection.peers.map(Uri::parse),
                    ReadingPositionSafSync.deviceId(context)).use { }
            } else folderStore(context, connection.uri).use { it.list() }
            activate(context, connection)
        }
        schedule(context)
        sync(context, db)
    }

    private fun folderStore(context: Context, tree: Uri): SharedDocumentStore {
        require(context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }) { "La carpeta central no tiene permiso persistente de lectura y escritura." }
        return SharedDocumentStore(context, tree)
    }
    private fun filePeers(context: Context): List<Uri> =
        prefs(context).getStringSet("file_peers", emptySet()).orEmpty().map(Uri::parse)
    private fun store(context: Context): SharedStore {
        val selected = requireNotNull(uri(context)) { "Conecta una biblioteca central." }
        return if (prefs(context).getString("mode", "folder") == "files")
            SharedFileStore(context, selected, filePeers(context), ReadingPositionSafSync.deviceId(context))
        else folderStore(context, selected)
    }
    fun fileMode(context: Context): Boolean = configured(context) && prefs(context).getString("mode", "folder") == "files"
    suspend fun connectFile(context: Context, db: AppDatabase, own: Uri, newDocument: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = currentConnection(context)
            require(old == null || old.uri != own || old.files) { "No se puede usar una carpeta como archivo de biblioteca." }
            require(own != BackupRoutine.status(context).uri) {
                "Ese archivo es tu copia de seguridad. Crea un archivo nuevo para la biblioteca."
            }
            val saved = previousConnections(context).firstOrNull { it.uri == own && it.files }
            val repairing = newDocument && needsRepair(context) && old?.files == true
            if (newDocument) require(!isLibraryDocument(context, own)) {
                "Selecciona un archivo NUEVO. El archivo anterior y los vinculados se conservarán."
            }
            val peers = when {
                repairing && old != null -> old.peers + old.uri.toString()
                old != null && old.uri == own && old.files -> old.peers
                else -> saved?.peers.orEmpty()
            }
            val accepted = SharedFileStore(context, own, peers.map(Uri::parse),
                ReadingPositionSafSync.deviceId(context), requireEmpty = newDocument).use {
                it.commit()
                // The old owner-mismatch archive remains read-only. Never change its owner.
                if (repairing) it.acceptedPeers.map(Uri::toString).toSet() else peers
            }
            activate(context, SavedConnection(own, true, accepted))
        }
        schedule(context)
        sync(context, db)
    }
    suspend fun addFilePeer(context: Context, db: AppDatabase, peer: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(fileMode(context)) { "Crea primero el contenedor propio." }
            val own = requireNotNull(uri(context))
            require(peer != BackupRoutine.status(context).uri) {
                "Has seleccionado una copia de seguridad, no el archivo del otro dispositivo."
            }
            require(peer != own) { "Has elegido el archivo de este dispositivo. Selecciona el que creó el otro móvil o tablet." }
            val peers = (filePeers(context) + peer).distinct()
            require(peers.size <= 10)
            SharedFileStore(context, own, peers, ReadingPositionSafSync.deviceId(context)).use {
                require(peer in it.acceptedPeers) {
                    it.readErrors.joinToString(" · ").ifBlank { "No se pudo verificar el archivo seleccionado." }
                }
            }
            check(prefs(context).edit().putStringSet("file_peers", peers.map(Uri::toString).toSet()).commit())
        }
        sync(context, db)
    }
    suspend fun removeFilePeer(context: Context, peer: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(fileMode(context))
            check(prefs(context).edit().putStringSet("file_peers",
                (filePeers(context) - peer).map(Uri::toString).toSet())
                .putString("status", "Conexiones modificadas. Pendiente de comprobar.").commit())
            changes.value += 1
        }
        schedule(context)
    }
    fun linkedFiles(context: Context): List<Uri> = filePeers(context).sortedBy { it.toString() }
    private fun publishStatus(context: Context, hashes: Set<String>, errors: List<String>) {
        val message = if (errors.isEmpty()) "Biblioteca comprobada: " + hashes.size + " originales."
            else "Sincronización parcial: " + errors.joinToString(" · ").take(1200)
        val edit = prefs(context).edit().putStringSet("hashes", hashes).putString("status", message)
        if (errors.isEmpty()) edit.putLong("last_success", System.currentTimeMillis()).remove("owner_mismatch")
        edit.apply()
        changes.value += 1
    }
    suspend fun connect(context: Context, db: AppDatabase, tree: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(uri(context) != tree || !fileMode(context)) { "El contenedor no es una carpeta." }
            folderStore(context, tree).use { it.probe() }
            activate(context, SavedConnection(tree, false, emptySet()))
        }
        schedule(context)
        sync(context, db)
    }

    private fun readBounded(store: SharedStore, item: SharedDocumentStore.Item): ByteArray =
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
    private fun inventory(store: SharedStore): Inventory {
        val files = store.list()
        val items = files.groupBy { it.name }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val states = mutableListOf<String>()
        val found = mutableListOf<Receipt>()
        val errors = store.readErrors.toMutableList()
        val perDevice = files.mapNotNull { item ->
            stateName.matchEntire(item.name)?.takeIf { it.groupValues[2].toLongOrNull() != null }?.let { it to item }
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

    private fun upload(store: SharedStore, book: BookEntity, file: File): Receipt {
        require(file.isFile && file.length() == book.sizeBytes &&
            OriginalLinker(file.parentFile ?: File(".")).sha256Hex(file) == book.contentHash) {
            "Original local no válido: " + book.title.take(80)
        }
        val name = "bia-book-" + book.contentHash + "-" + UUID.randomUUID() + ".bin"
        file.inputStream().use { store.create(name, "application/octet-stream", it, book.contentHash, book.sizeBytes) }
        return Receipt(book.contentHash, name, book.sizeBytes)
    }
    private suspend fun publish(owner: String, store: SharedStore, db: AppDatabase, receipts: Collection<Receipt>) {
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
        val own = store.list().mapNotNull { item ->
            stateName.matchEntire(item.name)?.takeIf { it.groupValues[1] == owner && it.groupValues[2].toLongOrNull() != null }?.let { it to item }
        }.maxByOrNull { it.first.groupValues[2].toLong() }
        if (own != null && own.first.groupValues[3] == hash &&
            runCatching { store.verify(own.second, hash, bytes.size.toLong()) }.getOrDefault(false)) return
        val seq = (own?.first?.groupValues?.get(2)?.toLong() ?: 0L) + 1L
        require(seq > 0L)
        store.create("bia-state-" + owner + "-" + seq + "-" + hash + "-" + UUID.randomUUID() + ".json",
            "application/json", bytes.inputStream(), hash, bytes.size.toLong())
    }
    internal data class StoreResult(val hashes: Set<String>, val errors: List<String>)
    suspend fun sync(context: Context, db: AppDatabase): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = store(context).use { syncStore(it, db, ReadingPositionSafSync.deviceId(context)) }
            if (result.errors.isEmpty()) prefs(context).edit()
                .putLong("last_publication", System.currentTimeMillis()).apply()
            publishStatus(context, result.hashes, result.errors)
            Result(result.hashes.size, result.errors)
        }
    }
    internal suspend fun syncStore(store: SharedStore, db: AppDatabase, owner: String): StoreResult {
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
            publish(owner, store, db, known.values.flatten())
            store.commit()
            val activeHashes = db.backupDao().booksAll().map { it.contentHash }.toSet()
            return StoreResult(available.intersect(activeHashes), errors)
    }
    suspend fun migrateZip(context: Context, db: AppDatabase, zip: Uri): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            store(context).use { store ->
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
                    if (book.contentHash !in available && book.contentHash !in content.missingHashes) {
                        val file = content.staged.getValue(CompleteBackupArchive.entryName(book.id))
                        known[book.contentHash] = known[book.contentHash].orEmpty() + upload(store, book, file)
                        available.add(book.contentHash)
                    }
                }
                BackupManager(db, BuildConfig.VERSION_NAME).restoreJson(content.json, synchronize = true)
                publish(ReadingPositionSafSync.deviceId(context), store, db, known.values.flatten())
                store.commit()
                val active = available.intersect(db.backupDao().booksAll().map { it.contentHash }.toSet())
                publishStatus(context, active, inv.errors)
                Result(active.size, inv.errors)
            } finally { stage.deleteRecursively() }
            }
        }
    }
    private fun verifiedCopy(store: SharedStore, inv: Inventory, book: BookEntity, target: File) {
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
            store(context).use { store ->
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
    }
    suspend fun deleteEverywhere(context: Context, db: AppDatabase, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val removed = com.javierrb35.bibliotecaia.data.BookDeletion.remove(db, id) ?: return@withLock
            // The logical deletion is committed before cleanup; stale remote copies cannot resurrect it.
            if (CentralCatalogue.local(removed)) runCatching { LocalBookCopyRemover.delete(context.filesDir, removed) }
            prefs(context).edit().putStringSet("hashes", hashes(context) - removed.contentHash).apply()
            changes.value += 1
        }
        schedule(context, immediate = true)
    }

    suspend fun evict(context: Context, db: AppDatabase, id: String) {
        val result = sync(context, db)
        require(result.errors.isEmpty()) { "Sincronización incompleta; no se retirará el original." }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store(context).use { store ->
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
    }
    /** Independent portable ZIP export can recover non-downloaded originals
     * from the shared folder without changing the local download state. */
    internal fun stageFromStore(store: SharedStore, book: BookEntity, file: File) =
        verifiedCopy(store, inventory(store), book, file)

    suspend fun stageOriginal(context: Context, book: BookEntity, file: File) = withContext(Dispatchers.IO) {
        mutex.withLock {
            store(context).use { store -> verifiedCopy(store, inventory(store), book, file) }
        }
    }
    suspend fun stageOriginals(context: Context, books: List<BookEntity>, directory: File,
                               skipUnavailable: Boolean = false): Map<String, File> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store(context).use { store ->
                    val inv = inventory(store)
                    books.mapNotNull { book ->
                        // A provider failure is not proof of absence. Only skip a book
                        // when inventory was read without errors and no receipt exists.
                        if (skipUnavailable && inv.receipts[book.contentHash].isNullOrEmpty() && inv.errors.isEmpty()) return@mapNotNull null
                        val target = File.createTempFile("central-original-", ".bin", directory)
                        verifiedCopy(store, inv, book, target)
                        book.contentHash to target
                    }.toMap()
                }
            }
        }
    internal suspend fun pullStore(store: SharedStore, db: AppDatabase): StoreResult {
        val inv = inventory(store)
        val errors = inv.errors.toMutableList()
        val manager = BackupManager(db, BuildConfig.VERSION_NAME)
        for (json in inv.states) {
            try { manager.restoreJson(json, synchronize = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors.add(e.message ?: "Catálogo incompatible.") }
        }
        val activeHashes = db.backupDao().booksAll().map { it.contentHash }.toSet()
        return StoreResult(inv.availableHashes().intersect(activeHashes), errors)
    }
    suspend fun pull(context: Context, db: AppDatabase): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = store(context).use { pullStore(it, db) }
            publishStatus(context, result.hashes, result.errors)
            Result(result.hashes.size, result.errors)
        }
    }

    // On leaving a reader, publish the already committed Room position now,
    // not just at the next periodic worker. One conflated queue prevents
    // simultaneous ZIP writers and redundant exit requests. The durable
    // WorkManager request remains the fallback for process death/offline.
    private val exitRequests = Channel<Context>(Channel.CONFLATED)
    private val exitPublisher = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    init {
        exitPublisher.launch {
            for (app in exitRequests) {
                if (!configured(app)) continue
                try { sync(app, AppDatabase.get(app)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { recordFailure(app, e) }
            }
        }
    }
    fun publishReadingExit(context: Context) {
        val app = context.applicationContext
        ReadingPositionSafSync.requestPublish(app)
        if (!configured(app) || ReadingPositionSafSync.fastReady(app)) return
        schedule(app, immediate = true)
        exitRequests.trySend(app)
    }
    fun recordFailure(context: Context, error: Exception) {
        val edit = prefs(context).edit().putString("status", "Pendiente de sincronizar: " + (error.message ?: "error"))
        if (error is SharedOwnerMismatch) edit.putBoolean("owner_mismatch", true)
        edit.apply()
        changes.value += 1
    }
    private val requestLock = Any()
    internal fun revision(context: Context): Long = prefs(context).getLong("requested_revision", 0L)
    fun schedule(context: Context, immediate: Boolean = false) {
        if (!configured(context)) return
        synchronized(requestLock) {
            val next = revision(context) + 1L
            prefs(context).edit().putLong("requested_revision", next).apply()
        }
        val work = WorkManager.getInstance(context.applicationContext)
        work.enqueueUniqueWork("shared-library-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SharedLibraryWorker>().setInitialDelay(if (immediate) 0L else 3L, TimeUnit.SECONDS).build())
        work.enqueueUniquePeriodicWork("shared-library-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SharedLibraryWorker>(15, TimeUnit.MINUTES).build())
    }
}

class SharedLibraryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SharedLibrary.configured(applicationContext)) return Result.success()
        return try {
            val revision = SharedLibrary.revision(applicationContext)
            val result = SharedLibrary.sync(applicationContext, AppDatabase.get(applicationContext))
            if (result.errors.isEmpty() && revision == SharedLibrary.revision(applicationContext)) {
                BackupRoutine.refreshOnLibraryOpen(applicationContext, BuildConfig.VERSION_NAME)
                Result.success()
            } else Result.retry()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            SharedLibrary.recordFailure(applicationContext, e)
            Result.retry()
        }
    }
}
