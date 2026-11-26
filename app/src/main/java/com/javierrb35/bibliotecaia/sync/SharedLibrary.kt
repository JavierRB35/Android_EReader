package com.javierrb35.bibliotecaia.sync

import android.content.Context
import android.net.Uri
import androidx.work.*
import com.javierrb35.bibliotecaia.BuildConfig
import com.javierrb35.bibliotecaia.backup.*
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.importer.LocalBookCopyRemover
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.ui.BookCoverLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
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
    val coverChanges = MutableStateFlow(0L)
    private data class CoverIndex(val tree: Uri, val items: List<SharedDocumentStore.Item>, val checkedAt: Long)
    @Volatile private var coverIndex: CoverIndex? = null
    private val coverIndexMutex = Mutex()

    private fun rememberCovers(tree: Uri, items: List<SharedDocumentStore.Item>) {
        val covers = items.filter { SharedCoverArt.isName(it.name) }
        val previous = coverIndex
        coverIndex = CoverIndex(tree, covers, android.os.SystemClock.elapsedRealtime())
        if (previous == null || previous.tree != tree || previous.items.toSet() != covers.toSet())
            coverChanges.value += 1
    }

    /** Fetch only derived thumbnails, independent of the original-audit mutex. */
    suspend fun readCover(context: Context, hash: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!namedFolderMode(context)) return@withContext null
        val tree = uri(context) ?: return@withContext null
        folderStore(context, tree, named = true).use { source ->
            val index = coverIndexMutex.withLock {
                val cached = coverIndex
                if (cached != null && cached.tree == tree &&
                    android.os.SystemClock.elapsedRealtime() - cached.checkedAt < 30_000L) cached
                else {
                    rememberCovers(tree, source.list())
                    coverIndex?.takeIf { it.tree == tree }
                }
            }
            if (uri(context) != tree) return@withContext null
            SharedCoverArt.read(source, index?.items.orEmpty(), hash)
        }
    }

    /** Separate browse-only index; listed receipts are not verified originals. */
    fun catalogueHashes(context: Context): Set<String> =
        prefs(context).getStringSet("catalogue_hashes", null)?.toSet() ?: hashes(context)

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
    // Persisted SAF grants allow local staging even when the provider is offline.
    // The actual publication and remote re-read verify effective access before eviction.
    fun writeAccess(context: Context): SharedWriteAccess =
        SharedWriteAccess.evaluate(configured(context), hasAccess(context), needsRepair(context))

    fun lastSuccess(context: Context): Long = prefs(context).getLong("last_success", 0L)
    fun healthy(context: Context): Boolean = hasAccess(context) &&
        prefs(context).getString("status", "").orEmpty().startsWith("Biblioteca comprobada:")
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

    data class SavedConnection(val uri: Uri, val files: Boolean, val peers: Set<String>, val named: Boolean = false) {
        val label: String get() = (if (files) "Contenedor" else "Carpeta") +
            " · " + (uri.lastPathSegment ?: uri.authority ?: "Almacenamiento").takeLast(90)
        internal fun encode() = JSONObject().put("uri", uri.toString()).put("files", files)
            .put("peers", org.json.JSONArray(peers.sorted())).put("named", named).toString()
        companion object {
            internal fun decode(value: String): SavedConnection {
                val obj = JSONObject(value)
                val peers = obj.getJSONArray("peers")
                return SavedConnection(Uri.parse(obj.getString("uri")), obj.getBoolean("files"),
                    (0 until peers.length()).map { peers.getString(it) }.toSet(), obj.optBoolean("named", false))
            }
        }
    }
    private fun currentConnection(context: Context): SavedConnection? = uri(context)?.let {
        SavedConnection(it, fileMode(context), filePeers(context).map(Uri::toString).toSet(), namedFolderMode(context))
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
            .putString("mode", if (connection.files) "files" else if (connection.named) "named_folder" else "folder")
            .putStringSet("file_peers", connection.peers)
            .putStringSet("previous_connections", history.map { it.encode() }.toSet())
            .remove("owner_mismatch")
        if (old?.uri != connection.uri || old?.named != connection.named) edit.remove("hashes").remove("catalogue_hashes").remove("last_success").remove("last_publication").putString("status", "Pendiente de sincronizar.")
        check(edit.commit()) { "No se pudo guardar la nueva conexión." }
        changes.value += 1
    }
    suspend fun restoreConnection(context: Context, db: AppDatabase, connection: SavedConnection) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(connection in previousConnections(context)) { "Conexión anterior no disponible." }
            if (connection.files) {
                SharedFileStore(context, connection.uri, connection.peers.map(Uri::parse),
                    ReadingPositionSafSync.deviceId(context)).use { }
            } else folderStore(context, connection.uri, connection.named).use { it.list() }
            activate(context, connection)
        }
        schedule(context)
        sync(context, db)
    }

    private fun folderStore(context: Context, tree: Uri, named: Boolean = false): SharedDocumentStore {
        require(context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }) { "La carpeta central no tiene permiso persistente de lectura y escritura." }
        return SharedDocumentStore(context, tree, named)
    }
    private fun filePeers(context: Context): List<Uri> =
        prefs(context).getStringSet("file_peers", emptySet()).orEmpty().map(Uri::parse)
    private fun store(context: Context): SharedStore {
        val selected = requireNotNull(uri(context)) { "Conecta una biblioteca central." }
        return if (prefs(context).getString("mode", "folder") == "files")
            SharedFileStore(context, selected, filePeers(context), ReadingPositionSafSync.deviceId(context))
        else folderStore(context, selected, namedFolderMode(context))
    }
    fun namedFolderMode(context: Context): Boolean = configured(context) && prefs(context).getString("mode", "folder") == "named_folder"
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
        if (errors.isEmpty()) edit.putStringSet("catalogue_hashes", hashes).putLong("last_success", System.currentTimeMillis()).remove("owner_mismatch")
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

    /**
     * Authorize the selected folder, preserve the current catalogue's original
     * files, then switch. Do NOT merge an old catalogue just to move its books.
     * A new folder starts without stale historical snapshots.
     */
    suspend fun relocateFolder(context: Context, db: AppDatabase, tree: Uri): Result =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val previous = uri(context)
                val owner = ReadingPositionSafSync.deviceId(context)
                val result = folderStore(context, tree).use { target ->
                    target.probe()
                    val available = inventory(target).availableHashes()
                    val missing = db.backupDao().booksAll().filter {
                        !CentralCatalogue.local(it) && it.contentHash !in available
                    }
                    if (missing.isNotEmpty()) {
                        require(previous != null && previous != tree) {
                            "Hay libros sin original disponible. Recupera o elimina sus fichas antes de continuar: " +
                                missing.take(5).joinToString { it.title }
                        }
                        store(context).use { source ->
                            val sourceInventory = inventory(source)
                            for (book in missing) {
                                val temp = File.createTempFile("move-original-", ".bin", context.cacheDir)
                                try {
                                    verifiedCopy(source, sourceInventory, book, temp)
                                    val linked = OriginalLinker(context.filesDir).link(temp, listOf(book))
                                        ?: error("No se pudo conservar el original de " + book.title)
                                    db.bookDao().update(linked)
                                } finally { temp.delete() }
                            }
                        }
                    }
                    val moved = syncStore(target, db, owner, context.filesDir)
                    require(moved.errors.isEmpty()) { moved.errors.joinToString(" · ") }
                    require(db.backupDao().booksAll().all { it.contentHash in moved.hashes }) {
                        "No se han podido comprobar todos los originales. Se conserva la conexión anterior."
                    }
                    activate(context, SavedConnection(tree, false, emptySet()))
                    publishStatus(context, moved.hashes, moved.errors)
                    moved
                }
                Result(result.hashes.size, result.errors)
            }.also { schedule(context) }
        }

    /**
     * Explicit new-layout migration. Do not create a second per-device ZIP or
     * convert an existing legacy tree in place. The old connection remains
     * available until all active originals are independently verified.
     */
    suspend fun connectNamedFolder(context: Context, db: AppDatabase, tree: Uri,
                                   onProgress: (String) -> Unit = {}): Result =
        withContext(Dispatchers.IO) {
            var stage = NamedFolderSetupFailure.Stage.FOLDER_ACCESS
            try {
            // Verify the selected destination independently of the previous
            // connection. A stale legacy URI must not masquerade as a Drive error.
            onProgress("Comprobando permisos de lectura y escritura…")
            folderStore(context, tree, named = true).use { it.probe() }
            onProgress("Integrando los datos de lectura anteriores…")
            stage = NamedFolderSetupFailure.Stage.LEGACY_PROGRESS
            // Preserve any newer positions from the previous standalone JSON channel.
            if (!namedFolderMode(context) && ReadingPositionSafSync.hasOwn(context)) {
                require(ReadingPositionSafSync.refresh(context, db)) {
                    "No se pudieron comprobar los avances anteriores. Se conserva la conexión anterior."
                }
            }
            mutex.withLock {
                val previous = currentConnection(context)
                val owner = ReadingPositionSafSync.deviceId(context)
                val result = folderStore(context, tree, named = true).use { target ->
                    stage = NamedFolderSetupFailure.Stage.TARGET_CATALOGUE
                    onProgress("Leyendo el catálogo compartido…")
                    val existing = inventory(target)
                    require(existing.errors.isEmpty()) { existing.errors.joinToString(" · ") }
                    require(existing.items.keys.none { objectName.matches(it) }) {
                        "Esta carpeta contiene originales del formato anterior. Elige una carpeta distinta para la biblioteca legible; la anterior se conservará."
                    }
                    // Pull old metadata before staging missing originals. Never delete
                    // either source files or local copies. A failed migration can retry.
                    if (previous != null && previous.uri != tree) {
                        stage = NamedFolderSetupFailure.Stage.LEGACY_LIBRARY
                        onProgress("Conservando originales de la ubicación anterior…")
                        store(context).use { source ->
                            val restored = pullStore(source, db, context.filesDir)
                            require(restored.errors.isEmpty()) { restored.errors.joinToString(" · ") }
                            val sourceInventory = inventory(source)
                            for (book in db.backupDao().booksAll()) {
                                if (CentralCatalogue.local(book) || SharedNamedOriginals.find(target, book) != null) continue
                                val temp = File.createTempFile("migrate-named-", ".bin", context.cacheDir)
                                try {
                                    verifiedCopy(source, sourceInventory, book, temp)
                                    val linked = OriginalLinker(context.filesDir).link(temp, listOf(book))
                                        ?: error("No se pudo conservar el original de " + book.title)
                                    db.bookDao().update(linked)
                                } finally { temp.delete() }
                            }
                        }
                    }
                    stage = NamedFolderSetupFailure.Stage.SYNC
                    val moved = syncStore(target, db, owner, context.filesDir, onProgress = onProgress)
                    require(moved.errors.isEmpty()) { moved.errors.joinToString(" · ") }
                    // Fresh post-publication inventory and full SHA-256 verification.
                    stage = NamedFolderSetupFailure.Stage.VERIFY
                    verifyNamedCatalogue(target, db.backupDao().booksAll(), onProgress)
                    stage = NamedFolderSetupFailure.Stage.SAVE
                    onProgress("Guardando la configuración verificada…")
                    activate(context, SavedConnection(tree, false, emptySet(), named = true))
                    publishStatus(context, moved.hashes, moved.errors)
                    moved
                }
                Result(result.hashes.size, result.errors)
            }.also { schedule(context) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { throw NamedFolderSetupFailure(stage, e) }
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
        val version = state.getInt("version")
        require(version in 1..2)
        val array = state.getJSONArray("originals")
        require(array.length() <= 10000)
        return (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            val receipt = Receipt(row.getString("hash"), row.getString("name"), row.getLong("bytes"))
            val legacy = objectName.matchEntire(receipt.name)?.groupValues?.get(1) == receipt.hash
            require((legacy || (version == 2 && SharedNamedOriginals.validName(receipt.name) &&
                receipt.hash.matches(Regex("[a-f0-9]{64}")))) && receipt.bytes in 1L..(512L * 1024 * 1024))
            receipt
        }
    }
    private data class Inventory(val receipts: Map<String, List<Receipt>>, val items: Map<String, List<SharedDocumentStore.Item>>,
                                 val states: List<String>, val errors: List<String>, val store: SharedStore) {
        fun resolve(receipt: Receipt): SharedDocumentStore.Item? = items[receipt.name].orEmpty().firstOrNull { item ->
            (item.size < 0 || item.size == receipt.bytes) && try {
                store.verify(item, receipt.hash, receipt.bytes)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        }
        fun available(hash: String): Boolean = receipts[hash].orEmpty().any {
            resolve(it) != null
        }
        fun availableHashes(): Set<String> = receipts.keys.filter(::available).toSet()
        fun listedHashes(): Set<String> = receipts.filterValues { candidates ->
            candidates.any { receipt -> items[receipt.name].orEmpty().any {
                !it.directory && (it.size < 0 || it.size == receipt.bytes)
            } }
        }.keys
    }
    private fun inventory(store: SharedStore): Inventory {
        val files = store.list()
        val items = files.groupBy { it.name }
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
        return Inventory(found.distinct().groupBy { it.hash }, items, states, errors, store)
    }

    /**
     * A provider may have committed the original before a catalogue publication
     * was interrupted. Reuse an existing immutable object only after verifying
     * its actual SHA-256 and size, never solely by filename. Do not delete
     * duplicates: other devices or historic recovery snapshots may reference them.
     * This reduces retry duplicates; it is not a cross-device atomic reservation.
     */
    private fun findVerifiedUnpublishedOriginal(store: SharedStore, inv: Inventory,
                                               book: BookEntity): Receipt? {
        return inv.items.values.asSequence().flatten().filter { item ->
            objectName.matchEntire(item.name)?.groupValues?.get(1) == book.contentHash &&
                (item.size < 0 || item.size == book.sizeBytes)
        }.sortedBy { it.name }.firstOrNull { item ->
            try { store.verify(item, book.contentHash, book.sizeBytes) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { false }
        }?.let { Receipt(book.contentHash, it.name, book.sizeBytes) }
    }

    private fun upload(store: SharedStore, book: BookEntity, file: File): Receipt {
        require(file.isFile && file.length() == book.sizeBytes &&
            OriginalLinker(file.parentFile ?: File(".")).sha256Hex(file) == book.contentHash) {
            "Original local no válido: " + book.title.take(80)
        }
        if (store.namedOriginals) {
            val item = SharedNamedOriginals.ensure(store, book, file)
            return Receipt(book.contentHash, item.name, book.sizeBytes)
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
        val version = if (store.namedOriginals) 2 else 1
        val bytes = JSONObject().put("version", version).put("snapshot", json)
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
        val nonce = if (store.namedOriginals) SharedStateRetention.nonce() else UUID.randomUUID().toString()
        store.create("bia-state-" + owner + "-" + seq + "-" + hash + "-" + nonce + ".json",
            "application/json", bytes.inputStream(), hash, bytes.size.toLong())
    }
    /** Keep three independently re-read generations from this installation.
     * Never delete legacy publications, peers, originals or uncertain documents.
     * Called only after successful full reconciliation and publication. */
    private fun compactManagedStates(store: SharedStore, owner: String) {
        if (!store.namedOriginals) return
        val own = store.list().mapNotNull { item ->
            val m = stateName.matchEntire(item.name) ?: return@mapNotNull null
            if (m.groupValues[1] != owner) return@mapNotNull null
            val seq = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
            seq to item
        }
        // Duplicate sequences may indicate a concurrent writer or stale listing.
        if (own.groupBy { it.first }.any { it.value.size != 1 }) return
        val managed = own.filter { SharedStateRetention.parse(it.second.name) != null }
            .sortedByDescending { it.first }
        if (managed.size <= SharedStateRetention.KEEP) return
        if (own.maxOf { it.first } != managed.first().first) return
        for ((_, item) in managed.take(SharedStateRetention.KEEP)) {
            val state = requireNotNull(SharedStateRetention.parse(item.name))
            val bytes = readBounded(store, item)
            require(SharedDocumentStore.hash(bytes) == state.hash) { "Estado de recuperación no verificado." }
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            BackupCodec.fromJson(root.getString("snapshot"))
            receipts(root)
        }
        var pending = false
        for ((_, item) in managed.drop(SharedStateRetention.KEEP).asReversed()) {
            // A single unreadable orphan must not prevent retirement of other
            // verified superseded generations. Preserve that orphan and report it.
            try {
                if (!store.retireManagedState(item, owner)) pending = true
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { pending = true }
        }
        check(!pending) { "No se pudo retirar alguna instantánea técnica antigua." }
    }

    /** Independent post-publication audit. Inventory.resolve already reads and hashes
     * the entire original: do not immediately read those same bytes a second time. */
    internal fun verifyNamedCatalogue(store: SharedStore, books: List<BookEntity>,
                                      onProgress: (String) -> Unit = {}) {
        onProgress("Comprobando el catálogo publicado…")
        val published = inventory(store)
        require(published.errors.isEmpty()) { published.errors.joinToString(" · ") }
        onProgress("Originales verificados: 0/${books.size}")
        for ((index, book) in books.withIndex()) {
            require(published.receipts[book.contentHash].orEmpty().any { receipt ->
                SharedNamedOriginals.validName(receipt.name) && receipt.bytes == book.sizeBytes &&
                    published.resolve(receipt) != null
            }) { "No se ha verificado el original de ${book.title.take(80)}; se conserva la conexión anterior." }
            onProgress("Originales verificados: ${index + 1}/${books.size}")
        }
    }

    internal data class StoreResult(val hashes: Set<String>, val errors: List<String>)
    suspend fun sync(context: Context, db: AppDatabase, expectedUri: Uri? = null): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (expectedUri != null) require(uri(context) == expectedUri) { "La ubicación de biblioteca ha cambiado." }
            val result = store(context).use { syncStore(it, db, ReadingPositionSafSync.deviceId(context), context.filesDir) }
            if (result.errors.isEmpty()) prefs(context).edit()
                .putLong("last_publication", System.currentTimeMillis()).apply()
            publishStatus(context, result.hashes, result.errors)
            Result(result.hashes.size, result.errors)
        }
    }
    /** Routine named-folder publication is a metadata operation, not an integrity audit.
     * A listed receipt is only a candidate; explicit download, eviction, migration and
     * full sync still verify original bytes. Repair unlisted originals using the old path. */
    suspend fun syncMetadata(context: Context, db: AppDatabase): Result = withContext(Dispatchers.IO) {
        if (!namedFolderMode(context)) return@withContext sync(context, db)
        mutex.withLock {
            val result = store(context).use {
                syncStore(it, db, ReadingPositionSafSync.deviceId(context), context.filesDir,
                    verifyOriginals = false)
            }
            val previouslyVerified = hashes(context).intersect(result.hashes)
            val edit = prefs(context).edit().putStringSet("hashes", previouslyVerified)
            if (result.errors.isEmpty()) {
                edit.putStringSet("catalogue_hashes", result.hashes)
                    .putLong("last_publication", System.currentTimeMillis())
                if (previouslyVerified != result.hashes || !healthy(context))
                    edit.putString("status", "Catálogo sincronizado: ${result.hashes.size} originales listados; auditoría de bytes independiente.")
            } else edit.putString("status", "Sincronización parcial: " + result.errors.joinToString(" · ").take(1200))
            edit.apply()
            changes.value += 1
            Result(previouslyVerified.size, result.errors)
        }
    }
    internal suspend fun syncStore(store: SharedStore, db: AppDatabase, owner: String,
                                   filesDir: File? = null, verifyOriginals: Boolean = true,
                                   onProgress: (String) -> Unit = {}): StoreResult {
            onProgress("Leyendo e integrando el catálogo compartido…")
            val inv = inventory(store)
            val errors = inv.errors.toMutableList()
            val manager = BackupManager(db, BuildConfig.VERSION_NAME, filesDir)
            for (json in inv.states) {
                try { manager.restoreJson(json, synchronize = true) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors.add("Catálogo no integrado: " + (e.message ?: "error")) }
            }
            val known = inv.receipts.toMutableMap()
            val available = if (store.namedOriginals) mutableSetOf<String>() else inv.availableHashes().toMutableSet()
            val listed = if (store.namedOriginals && !verifyOriginals) inv.listedHashes() else emptySet()
            // Existing candidates come from this inventory only. Creation still uses a
            // fresh listing and identity checks; a final independent audit catches changes.
            val candidates = inv.items.values.flatten()
            val books = db.backupDao().booksAll()
            onProgress("Originales procesados: 0/${books.size} · 0 confirmados")
            for ((index, book) in books.withIndex()) {
                try {
                    if (store.namedOriginals && (if (verifyOriginals) inv.available(book.contentHash)
                            else book.contentHash in listed)) available.add(book.contentHash)
                    if (book.contentHash in available) continue
                    if (!store.namedOriginals && !CentralCatalogue.local(book)) continue
                    val existing = if (store.namedOriginals) SharedNamedOriginals.find(store, book, candidates) else null
                    val receipt = if (existing != null) Receipt(book.contentHash, existing.name, book.sizeBytes)
                        else if (!CentralCatalogue.local(book)) {
                            if (store.namedOriginals) errors.add(book.title.take(80) + ": original no disponible.")
                            continue
                        } else if (store.namedOriginals) upload(store, book, File(book.localPath))
                        else findVerifiedUnpublishedOriginal(store, inv, book)
                            ?: upload(store, book, File(book.localPath))
                    known[book.contentHash] = (known[book.contentHash].orEmpty() + receipt).distinct()
                    available.add(book.contentHash)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors.add(book.title.take(80) + ": " + (e.message ?: "no se pudo subir")) }
                finally {
                    onProgress("Originales procesados: ${index + 1}/${books.size} · ${available.size} confirmados")
                }
            }
            // A failed upload does not block the other books or reading state.
            onProgress("Publicando el catálogo de ${books.size} libros…")
            publish(owner, store, db, known.values.flatten())
            store.commit()
            if (errors.isEmpty()) {
                onProgress("Comprobando las instantáneas de recuperación…")
                try { compactManagedStates(store, owner) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors.add("Compactación pendiente: " + (e.message ?: "error")) }
            }
            val activeHashes = db.backupDao().booksAll().map { it.contentHash }.toSet()
            return StoreResult(available.intersect(activeHashes), errors)
    }
    suspend fun migrateZip(context: Context, db: AppDatabase, zip: Uri,
                           onProgress: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(namedFolderMode(context) && hasAccess(context)) {
                "Conecta primero la carpeta compartida de la biblioteca."
            }
            store(context).use { store ->
                val stage = File(context.cacheDir, "shared-migrate-" + UUID.randomUUID())
                check(stage.mkdirs())
                try {
                    onProgress("Comprobando la copia ZIP y sus originales…")
                    val content = context.contentResolver.openInputStream(zip)?.use {
                        CompleteBackupArchive.read(it, stage)
                    } ?: throw IOException("No se puede leer el ZIP.")
                    val result = migrateZipStore(store, db, ReadingPositionSafSync.deviceId(context),
                        content, context.filesDir, onProgress)
                    publishStatus(context, result.hashes, result.errors)
                    Result(result.hashes.size, result.errors)
                } finally { stage.deleteRecursively() }
            }
        }.also { schedule(context, immediate = true) }
    }

    /** Publish verified originals to the shared folder; never install ZIP
     * originals into filesDir/books. Preserve previously downloaded paths. */
    internal suspend fun migrateZipStore(store: SharedStore, db: AppDatabase, owner: String,
                                        content: CompleteBackupArchive.Contents, filesDir: File? = null,
                                        onProgress: (String) -> Unit = {}): StoreResult {
        require(store.namedOriginals) { "La restauración necesita una carpeta compartida." }
        val inv = inventory(store)
        require(inv.errors.isEmpty()) { inv.errors.joinToString(" · ") }
        val manager = BackupManager(db, BuildConfig.VERSION_NAME, filesDir)
        onProgress("Integrando el catálogo compartido…")
        for (json in inv.states) manager.restoreJson(json, synchronize = true)
        val snapshot = BackupCodec.fromJson(content.json)
        val known = inv.receipts.toMutableMap()
        val available = inv.availableHashes().toMutableSet()
        val errors = mutableListOf<String>()
        val candidates = inv.items.values.flatten()
        for ((index, book) in snapshot.books.withIndex()) {
            if (book.contentHash !in available) {
                val existing = SharedNamedOriginals.find(store, book, candidates)
                val receipt = when {
                    existing != null -> Receipt(book.contentHash, existing.name, book.sizeBytes)
                    book.contentHash !in content.missingHashes -> {
                        val file = content.staged.getValue(CompleteBackupArchive.entryName(book.id))
                        upload(store, book, file)
                    }
                    else -> null
                }
                if (receipt == null) errors.add(book.title.take(80) + ": original no incluido en la copia ni verificado en Drive.")
                else {
                    known[book.contentHash] = (known[book.contentHash].orEmpty() + receipt).distinct()
                    available.add(book.contentHash)
                }
            }
            onProgress("Originales de la copia procesados: ${index + 1}/${snapshot.books.size}")
        }
        // No availableOriginals paths: all ZIP originals remain in Drive.
        manager.restoreJson(content.json, synchronize = true)
        onProgress("Publicando y verificando el catálogo compartido…")
        publish(owner, store, db, known.values.flatten())
        store.commit()
        val published = inventory(store)
        require(published.errors.isEmpty()) { published.errors.joinToString(" · ") }
        val active = db.backupDao().booksAll().map { it.contentHash }.toSet()
        val confirmed = available.intersect(active)
        require(published.listedHashes().containsAll(confirmed)) {
            "El catálogo o los originales aún no aparecen en la carpeta. Conserva el ZIP y reintenta."
        }
        return StoreResult(confirmed, errors)
    }
    private fun verifiedCopy(store: SharedStore, inv: Inventory, book: BookEntity, target: File) {
        for (receipt in inv.receipts[book.contentHash].orEmpty()) {
            if (receipt.bytes != book.sizeBytes) continue
            for (item in inv.items[receipt.name].orEmpty()) {
                if (item.directory || (item.size >= 0 && item.size != receipt.bytes)) continue
                try {
                    // extract independently checks SHA-256 and length while copying.
                    // Do not open the entire remote original a second time to resolve it.
                    store.extract(item, target, book.contentHash, book.sizeBytes)
                    return
                } catch (e: CancellationException) { target.delete(); throw e }
                catch (e: InterruptedException) { target.delete(); throw e }
                catch (_: Exception) { target.delete() }
            }
        }
        throw IOException("No se encuentra un original central íntegro de " + book.title.take(80))
    }
    suspend fun download(context: Context, db: AppDatabase, hash: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            store(context).use { store ->
            val inv = inventory(store)
            val manager = BackupManager(db, BuildConfig.VERSION_NAME, context.filesDir)
            for (json in inv.states) {
                try { manager.restoreJson(json, synchronize = true) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Isolate invalid peer metadata. */ }
            }
            val book = db.bookDao().findByHash(hash) ?: error("Libro no encontrado.")
            require(book.format != BookFormat.PDF) { "PDF histórico; no se descarga desde el lector." }
            if (CentralCatalogue.local(book) &&
                OriginalLinker(context.filesDir).sha256Hex(File(book.localPath)) == hash) return@withLock
            val file = File.createTempFile("shared-download-", ".tmp", context.cacheDir)
            try {
                runInterruptible { verifiedCopy(store, inv, book, file) }
                coroutineContext.ensureActive()
                val linked = OriginalLinker(context.filesDir).link(file, listOf(book))
                    ?: error("No se puede colocar el original en el dispositivo.")
                // Local link is a verified, atomic copy; commit only that book.
                db.bookDao().update(linked)
                changes.value += 1
            } finally { file.delete() }
            }
        }
    }
    suspend fun deleteEverywhere(context: Context, db: AppDatabase, id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val removed = com.javierrb35.bibliotecaia.data.BookDeletion.remove(db, id) ?: return@withLock
            // The logical deletion is committed before cleanup; stale remote copies cannot resurrect it.
            if (CentralCatalogue.local(removed)) runCatching { LocalBookCopyRemover.delete(context.filesDir, removed) }
            prefs(context).edit().putStringSet("hashes", hashes(context) - removed.contentHash)
                .putStringSet("catalogue_hashes", catalogueHashes(context) - removed.contentHash).apply()
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
                    val item = inv.resolve(receipt)
                    item != null && receipt.bytes == book.sizeBytes &&
                        runCatching { store.verify(item, book.contentHash, book.sizeBytes) }.getOrDefault(false)
                }) { "No se pudo comprobar el original central. Se conserva el archivo local." }
                publishLocalCover(store, book)
                LocalBookCopyRemover.delete(context.filesDir, book)
                check(db.bookDao().clearLocalPath(id, book.localPath) == 1)
                }
            }
        }
    }
    /** Caller has just published the shared catalogue. Validate every remote
     * original again before removing any of this batch's temporary local files.
     * A separate backup is optional and is never a publication prerequisite.
     * Never touch a pre-existing downloaded copy (the importer rejects duplicates). */
    suspend fun evictVerifiedImports(context: Context, db: AppDatabase, expectedUri: Uri,
                                     books: List<BookEntity>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(uri(context) == expectedUri) { "La ubicación de biblioteca ha cambiado." }
            store(context).use { store -> evictVerifiedBatch(store, db, context.filesDir, books) }
            changes.value += 1
        }
    }

    internal suspend fun evictVerifiedBatch(store: SharedStore, db: AppDatabase, filesDir: File,
                                            books: List<BookEntity>) {
        val inv = inventory(store)
        require(inv.errors.isEmpty()) { "No se ha podido verificar la biblioteca." }
        val latest = books.map { imported ->
            val book = db.bookDao().findById(imported.id) ?: error("No se encuentra el libro importado.")
            require(book.localPath == imported.localPath && book.contentHash == imported.contentHash &&
                book.sizeBytes == imported.sizeBytes && CentralCatalogue.local(book)) { "El original local ha cambiado." }
            require(inv.receipts[book.contentHash].orEmpty().any { receipt ->
                val item = inv.resolve(receipt)
                item != null && receipt.bytes == book.sizeBytes &&
                    runCatching { store.verify(item, book.contentHash, book.sizeBytes) }.getOrDefault(false)
            }) { "No se pudo comprobar el original central de " + book.title }
            book
        }
        for (book in latest) {
            publishLocalCover(store, book)
            LocalBookCopyRemover.delete(filesDir, book)
            check(db.bookDao().clearLocalPath(book.id, book.localPath) == 1)
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
    internal suspend fun pullStore(store: SharedStore, db: AppDatabase, filesDir: File? = null): StoreResult =
        readCatalogueStore(store, db, filesDir, verifyOriginals = true)

    internal suspend fun readCatalogueStore(store: SharedStore, db: AppDatabase,
                                           filesDir: File? = null, verifyOriginals: Boolean = false): StoreResult {
        val inv = inventory(store)
        val errors = inv.errors.toMutableList()
        val manager = BackupManager(db, BuildConfig.VERSION_NAME, filesDir)
        for (json in inv.states) {
            try { manager.restoreJson(json, synchronize = true) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { errors.add(e.message ?: "Catálogo incompatible.") }
        }
        val activeHashes = db.backupDao().booksAll().map { it.contentHash }.toSet()
        val found = if (verifyOriginals) inv.availableHashes() else inv.listedHashes()
        return StoreResult(found.intersect(activeHashes), errors)
    }

    /** Merge metadata without opening original streams or updating verified hashes. */
    suspend fun refreshCatalogue(context: Context, db: AppDatabase): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            val tree = uri(context) ?: error("Conecta una biblioteca central.")
            val result = store(context).use { source ->
                val result = readCatalogueStore(source, db, context.filesDir)
                if (source.namedOriginals && result.errors.isEmpty()) rememberCovers(tree, source.list())
                result
            }
            if (result.errors.isEmpty()) {
                prefs(context).edit().putStringSet("catalogue_hashes", result.hashes).apply()
                changes.value += 1
            }
            Result(result.hashes.size, result.errors)
        }
    }

    private fun publishLocalCover(store: SharedStore, book: BookEntity) {
        if (!store.namedOriginals || book.format != BookFormat.EPUB || !CentralCatalogue.local(book)) return
        try {
            val listing = store.list()
            if (SharedCoverArt.read(store, listing, book.contentHash) != null) return
            val file = File(book.localPath)
            if (file.length() != book.sizeBytes ||
                OriginalLinker(file.parentFile ?: File(".")).sha256Hex(file) != book.contentHash) return
            val bitmap = BookCoverLoader.extract(book.format, file) ?: return
            val bytes = try { BookCoverLoader.jpeg(bitmap) } finally { bitmap.recycle() }
            if (bytes != null) SharedCoverArt.publish(store, book.contentHash, bytes, listing)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Derived covers must not block verified originals. */ }
    }

    /** Progressive historical backfill outside the UI. Never download a book
     * merely because a grid cell is visible. Keep batches bounded. */
    internal suspend fun maintainCovers(context: Context, db: AppDatabase) = withContext(Dispatchers.IO) {
        if (!namedFolderMode(context)) return@withContext
        val tree = uri(context) ?: return@withContext
        try {
            folderStore(context, tree, named = true).use { source ->
                val inv = inventory(source)
                if (inv.errors.isNotEmpty()) return@withContext
                val listed = inv.listedHashes()
                val already = inv.items.keys.mapNotNull(SharedCoverArt::bookHash).toHashSet()
                val absence = context.getSharedPreferences("cover_absence_v1", Context.MODE_PRIVATE)
                val books = db.backupDao().booksAll().filter {
                    it.format == BookFormat.EPUB && it.contentHash !in already &&
                        (CentralCatalogue.local(it) || it.contentHash in listed) &&
                        System.currentTimeMillis() - absence.getLong(it.contentHash, 0) > 7L * 24 * 60 * 60 * 1000
                }.sortedByDescending(CentralCatalogue::local)
                val deadline = android.os.SystemClock.elapsedRealtime() + 60_000L
                for (book in books.take(16)) {
                    if (uri(context) != tree || android.os.SystemClock.elapsedRealtime() >= deadline) break
                    var temp: File? = null
                    try {
                        val original = if (CentralCatalogue.local(book)) {
                            File(book.localPath).also {
                                require(it.length() == book.sizeBytes &&
                                    OriginalLinker(it.parentFile ?: File(".")).sha256Hex(it) == book.contentHash)
                            }
                        } else {
                            File.createTempFile("cover-original-", ".tmp", context.cacheDir).also {
                                temp = it
                                verifiedCopy(source, inv, book, it)
                            }
                        }
                        val bitmap = BookCoverLoader.extract(book.format, original)
                        if (bitmap == null) {
                            absence.edit().putLong(book.contentHash, System.currentTimeMillis()).apply()
                            continue
                        }
                        val bytes = try { BookCoverLoader.jpeg(bitmap) } finally { bitmap.recycle() }
                        if (bytes != null && uri(context) == tree)
                            SharedCoverArt.publish(source, book.contentHash, bytes)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Retry later, independently of original sync. */ }
                    finally { temp?.delete() }
                }
                if (uri(context) == tree) rememberCovers(tree, source.list())
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* No cover failure changes original integrity. */ }
    }

    suspend fun pull(context: Context, db: AppDatabase): Result = withContext(Dispatchers.IO) {
        mutex.withLock {
            val result = store(context).use { pullStore(it, db, context.filesDir) }
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
                try { syncMetadata(app, AppDatabase.get(app)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { recordFailure(app, e) }
            }
        }
    }
    fun publishReadingExit(context: Context) {
        val app = context.applicationContext
        if (!namedFolderMode(app)) ReadingPositionSafSync.requestPublish(app)
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
    fun schedule(context: Context, immediate: Boolean = false, delaySeconds: Long = 3L) {
        if (!configured(context)) return
        synchronized(requestLock) {
            val next = revision(context) + 1L
            prefs(context).edit().putLong("requested_revision", next).apply()
        }
        val work = WorkManager.getInstance(context.applicationContext)
        work.enqueueUniqueWork("shared-library-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SharedLibraryWorker>().setInitialDelay(if (immediate) 0L else delaySeconds, TimeUnit.SECONDS).build())
        work.enqueueUniquePeriodicWork("shared-library-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SharedLibraryWorker>(15, TimeUnit.MINUTES).build())
    }
}

class SharedLibraryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!SharedLibrary.configured(applicationContext)) return Result.success()
        return try {
            val revision = SharedLibrary.revision(applicationContext)
            val db = AppDatabase.get(applicationContext)
            val result = SharedLibrary.syncMetadata(applicationContext, db)
            SharedLibrary.maintainCovers(applicationContext, db)
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
