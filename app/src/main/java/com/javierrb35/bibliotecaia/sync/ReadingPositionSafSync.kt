package com.javierrb35.bibliotecaia.sync

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.TimeUnit
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.ReadingPositionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * SAF-only reading-position exchange. No app Google login and no shared
 * multi-writer JSON. A device writes ONLY the document it created and reads
 * selected peer documents. All operations are serialized in this process.
 */
object ReadingPositionSafSync {
    private const val PREFS = "biblioteca_position_saf_sync"
    private const val OWN = "own"
    private const val PEERS = "peers"
    private const val DEVICE = "device"
    private const val LAST_SYNC = "last_sync"
    private const val LAST_ERROR = "last_error"
    private const val LAST_PUBLISHED = "last_published"
    private const val BOOTSTRAP = "shared_bootstrap"
    private const val FOLDER = "position_folder"
    private const val AUTO_PEERS = "auto_peers"
    private const val REVISION = "publish_revision"
    private val requestLock = Any()
    val changes = MutableStateFlow(0L)
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    internal fun revision(context: Context) = prefs(context).getLong(REVISION, 0L)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val publicationRequests = Channel<Context>(Channel.CONFLATED)

    init {
        scope.launch {
            for (context in publicationRequests) {
                // Readers already debounce scroll events. Do not add another
                // delay or wait for the library ZIP / WorkManager.
                try { publish(context, AppDatabase.get(context)) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error(context, e) }
            }
        }
    }

    data class Status(val ownConfigured: Boolean, val peerCount: Int,
                      val lastSuccess: Long, val lastError: String, val lastPublished: Long)

    // UI, reader and WorkManager can request the first ID concurrently.
    // Never generate two identities for the same installation.
    @Synchronized
    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(DEVICE, null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "")
        check(prefs.edit().putString(DEVICE, id).commit()) {
            "No se pudo conservar la identidad local del dispositivo."
        }
        return id
    }

    fun suggestedName(context: Context): String =
        "BibliotecaIA-lectura-${deviceId(context).take(8)}.json"

    fun status(context: Context): Status {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Status(hasOwn(context), maxOf(peers(context).size, prefs.getInt(AUTO_PEERS, 0)),
            prefs.getLong(LAST_SYNC, 0L), prefs.getString(LAST_ERROR, "").orEmpty(),
            prefs.getLong(LAST_PUBLISHED, 0L))
    }

    fun hasOwn(context: Context): Boolean = own(context)?.let { StorageGrants.allows(context, it, true) } == true
    fun folder(context: Context): Uri? = prefs(context).getString(FOLDER, null)?.let(Uri::parse)
        ?.takeIf { StorageGrants.allows(context, it, true) }

    /** A granted folder supports automatic creation and discovery. Existing own
     * files are never replaced or relocated merely by running setup. */
    suspend fun prepareFolder(context: Context, database: AppDatabase, tree: Uri, unified: Boolean = false) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        require(StorageGrants.allows(app, tree, true)) { "No hay permiso para esta carpeta." }
        mutex.withLock {
            val id = deviceId(app)
            if (!hasOwn(app) || (unified && !StorageGrants.belongs(own(app), tree))) {
                val candidates = SharedDocumentStore(app, tree).use { it.list() }
                    .filter { it.name.startsWith("BibliotecaIA-lectura-") && it.name.endsWith(".json", true) }
                val valid = candidates.filter { item ->
                    runCatching { read(app, item.uri, false)?.deviceId == id }.getOrDefault(false)
                }
                require(valid.size <= 1) { "Hay más de un archivo de lectura propio. Selecciona el correcto desde el asistente." }
                val target = valid.singleOrNull()?.uri ?: StorageGrants.create(app, tree, "application/json", suggestedName(app))
                val old = own(app)
                require(old == null || old == target || (!emergency(app, old).exists() &&
                    !File(app.filesDir, "reading-position-sync-rollback.json").exists())) {
                    "Hay una recuperación pendiente del archivo anterior. No se sustituirá."
                }
                val data = read(app, target, allowEmpty = true)
                require(data == null || data.deviceId == id) { "No se modificará un archivo de otro dispositivo." }
                val edit = prefs(app).edit().putString(OWN, target.toString()).remove(BOOTSTRAP)
                if (unified) edit.remove(PEERS).remove(AUTO_PEERS)
                check(edit.commit())
                recoverPending(app, target)
                publishLocked(app, database)
                published(app)
            }
            val edit = prefs(app).edit().putString(FOLDER, tree.toString())
            if (folder(app) != tree) edit.remove(AUTO_PEERS).remove(BOOTSTRAP)
            if (unified) edit.remove(PEERS)
            check(edit.commit())
        }
        refreshForRead(app, database)
    }

    fun inFolder(context: Context, tree: Uri): Boolean =
        hasOwn(context) && StorageGrants.belongs(own(context), tree) && folder(context) == tree

    /** No dialog or picker is necessary when a shared folder is already granted. */
    suspend fun ensureAutomaticFolder(context: Context, database: AppDatabase) {
        val app = context.applicationContext
        if (!SharedLibrary.configured(app) || SharedLibrary.fileMode(app) ||
            !SharedLibrary.hasAccess(app)) return
        val tree = SharedLibrary.uri(app) ?: return
        if (hasOwn(app) && folder(app) != null) return
        try { prepareFolder(app, database, tree) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error(app, e) }
    }

    private fun folderCandidates(context: Context): List<Uri> {
        val tree = folder(context) ?: return emptyList()
        return SharedDocumentStore(context, tree).use { store ->
            store.list().filter {
                it.name.startsWith("BibliotecaIA-lectura-") && it.name.endsWith(".json", true)
            }.map { it.uri }
        }
    }

    /** File-only grants cannot create sibling files in Drive. Keep the existing
     * library path as fallback until every linked device has a JSON channel. */
    fun fastReady(context: Context): Boolean {
        val status = status(context)
        val expected = if (SharedLibrary.fileMode(context)) SharedLibrary.peerCount(context) else 1
        return status.ownConfigured && status.peerCount >= maxOf(1, expected)
    }

    private fun connectionKey(context: Context): String =
        listOf(SharedLibrary.uri(context)?.toString().orEmpty(), own(context)?.toString().orEmpty(),
            SharedLibrary.peerCount(context).toString(), folder(context)?.toString().orEmpty(),
            peers(context).map(Uri::toString).sorted().joinToString("|"))
            .joinToString("\n")

    /** Upgrade without losing a newer position still present only in a ZIP.
     * A single verified legacy pull seeds the JSON channel per configuration. */
    suspend fun refreshForRead(context: Context, database: AppDatabase): Boolean {
        val app = context.applicationContext
        if (!SharedLibrary.configured(app)) return refresh(app, database)
        val ready = fastReady(app)
        val key = connectionKey(app)
        val bootstrapped = prefs(app).getString(BOOTSTRAP, null) == key
        if (ready && bootstrapped && refresh(app, database)) return true
        // During migration or a JSON error, preserve the previous mechanism.
        val zipOk = try {
            SharedLibrary.pull(app, database).errors.isEmpty()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { SharedLibrary.recordFailure(app, e); false }
        val jsonOk = if (hasOwn(app)) refresh(app, database) else false
        if (ready && zipOk && jsonOk && publish(app, database)) {
            prefs(app).edit().putString(BOOTSTRAP, key).apply()
            return true
        }
        return if (ready) false else zipOk && (!hasOwn(app) || jsonOk)
    }

    private fun own(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(OWN, null)?.let(Uri::parse)

    private fun peers(context: Context): List<Uri> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(PEERS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Uri.parse(arr.getString(it)) }.distinct()
        } catch (_: Exception) { emptyList() }
    }

    /** Persisted grants must come from the system picker, not inferred from a Drive account. */
    private fun requireGrant(context: Context, uri: Uri, write: Boolean) {
        require(uri.scheme == "content") { "Selecciona un archivo mediante Android." }
        check(StorageGrants.allows(context, uri, write)) { "Sin permiso persistente para ${if (write) "leer y escribir" else "leer"} el archivo." }
    }

    /**
     * ACTION_CREATE_DOCUMENT creates a distinct writer-owned document. Do not
     * permit adoption of somebody else's file as the local writer.
     */
    suspend fun setOwn(context: Context, database: AppDatabase, uri: Uri) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val app = context.applicationContext
                requireGrant(app, uri, write = true)
                require(!SharedLibrary.isLibraryDocument(app, uri) &&
                    uri != com.javierrb35.bibliotecaia.backup.BackupRoutine.status(app).uri) {
                    "Selecciona un JSON de posiciones, no un ZIP de biblioteca o de seguridad."
                }
                val old = own(app)
                if (old != null && old != uri) {
                    requireGrant(app, old, write = true)
                    recoverPending(app, old)
                }
                val current = read(app, uri, allowEmpty = true)
                val id = deviceId(app)
                require(current == null || current.deviceId == id) {
                    "Este archivo pertenece a otro dispositivo. Vincúlalo como archivo de lectura."
                }
                check(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(OWN, uri.toString()).remove(BOOTSTRAP)
                    .remove(LAST_PUBLISHED).putString(LAST_ERROR, "").commit()) {
                    "No se pudo registrar el archivo propio."
                }
                publishLocked(app, database)
                published(app)
                changes.value += 1
            }
        }

    /** Peer files are always opened read-only. Never write to a peer URI. */
    suspend fun addPeer(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            requireGrant(app, uri, write = false)
            require(uri != own(app)) { "Tu archivo propio no es otro dispositivo." }
            require(!SharedLibrary.isLibraryDocument(app, uri) &&
                uri != com.javierrb35.bibliotecaia.backup.BackupRoutine.status(app).uri) {
                "Selecciona el JSON de posiciones del otro dispositivo."
            }
            val existing = peers(app).filter { it != uri }.map { readPeer(app, it, emptySet()).deviceId }.toSet()
            val data = readPeer(app, uri, existing)
            require(data.deviceId != deviceId(app)) {
                "Ese archivo también pertenece a este dispositivo."
            }
            val all = (peers(app) + uri).distinct()
            check(all.size <= 10) { "Se ha alcanzado el límite de dispositivos vinculados." }
            check(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PEERS, JSONArray(all.map(Uri::toString)).toString())
                .remove(BOOTSTRAP).putString(LAST_ERROR, "").commit()) {
                "No se pudo registrar el otro dispositivo."
            }
        }
    }

    fun unlinkPeers(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(PEERS).remove(BOOTSTRAP).remove(FOLDER).remove(AUTO_PEERS).apply()
        changes.value += 1
    }

    /**
     * Library entry pulls BEFORE the ZIP backup routine is allowed to start.
     * A peer read failure blocks the subsequent automatic ZIP refresh; it must
     * never be treated as proof that local data are the latest.
     */
    suspend fun refresh(context: Context, database: AppDatabase): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val app = context.applicationContext
                if (own(app) == null) {
                    if (peers(app).isEmpty()) return@withLock true
                    error(app, IllegalStateException("Falta crear el archivo propio de este dispositivo."))
                    return@withLock false
                }
                try {
                    val ownUri = requireNotNull(own(app))
                    requireGrant(app, ownUri, write = true)
                    recoverPending(app, ownUri)
                    val id = deviceId(app)
                    val ownData = read(app, ownUri, allowEmpty = true)
                    check(ownData == null || ownData.deviceId == id) {
                        "El archivo propio fue sustituido por el de otro dispositivo."
                    }
                    val seen = hashSetOf(id)
                    val documents = mutableListOf<ReadingPositionDocument>()
                    ownData?.let { documents.add(it) }
                    val errors = mutableListOf<String>()
                    for (peerUri in peers(app)) {
                        try {
                            val data = readPeer(app, peerUri, seen)
                            seen.add(data.deviceId)
                            documents.add(data)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { errors.add(e.message ?: "Archivo vinculado inaccesible.") }
                    }
                    // The same folder may expose an already selected peer through
                    // a different URI. Deduplicate by validated document owner.
                    try {
                        for (candidate in folderCandidates(app).filter { it != ownUri && it !in peers(app) }) {
                            try {
                                val data = read(app, candidate, false)
                                    ?: continue
                                if (data.deviceId == id) continue
                                if (seen.add(data.deviceId)) documents.add(data)
                                else if (documents.none { it == data }) {
                                    errors.add("Hay dos estados distintos del mismo dispositivo en la carpeta.")
                                    documents.add(data)
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { errors.add(e.message ?: "Archivo de lectura inaccesible.") }
                        }
                        prefs(app).edit().putInt(AUTO_PEERS, seen.size - 1).apply()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { errors.add(e.message ?: "No se pudo consultar la carpeta de lectura.") }
                    mergePositions(database, documents.flatMap { it.positions })
                    // Read-only: opening a reader does not upload a stale position.
                    if (errors.isNotEmpty()) {
                        error(app, IOException(errors.joinToString(" · ")))
                        false
                    } else {
                        success(app)
                        true
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    error(app, failure)
                    false
                }
            }
        }

    internal suspend fun mergePositions(database: AppDatabase, records: List<ReadingPositionRecord>) {
        val latest = ReadingPositionFileCodec.newest(records)
        database.withTransaction {
            val books = database.backupDao().booksAll().associateBy { it.contentHash }
            val deletions = database.backupDao().deletedBooksAll().associate { it.contentHash to it.deletedAt }
            for ((hash, incoming) in latest) {
                val book = books[hash] ?: continue
                if (incoming.updatedAt <= (deletions[hash] ?: Long.MIN_VALUE)) continue
                val existing = database.backupDao().positionFor(book.id)
                // Same deterministic tie rule as the ZIP restore; no fresh timestamp
                // for a received record or a book left open without movement.
                if (existing == null || incoming.updatedAt > existing.updatedAt ||
                    (incoming.updatedAt == existing.updatedAt &&
                        (incoming.locator > existing.locator ||
                            (incoming.locator == existing.locator && incoming.progress > existing.progress)))) {
                    database.backupDao().insertPositionReplace(
                        ReadingPositionEntity(book.id, incoming.locator, incoming.progress, incoming.updatedAt))
                    database.backupDao().updateBookProgressAfterRestore(book.id, incoming.progress)
                }
            }
        }
    }

    /** Immediate in-process send, plus durable retry when offline or killed. */
    fun requestPublish(context: Context) {
        val app = context.applicationContext
        if (own(app) == null) return
        synchronized(requestLock) {
            prefs(app).edit().putLong(REVISION, revision(app) + 1L).apply()
        }
        publicationRequests.trySend(app)
        val work = WorkManager.getInstance(app)
        work.enqueueUniqueWork("reading-position-json-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReadingPositionWorker>().setInitialDelay(5, TimeUnit.SECONDS).build())
        work.enqueueUniquePeriodicWork("reading-position-json-retry", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ReadingPositionWorker>(15, TimeUnit.MINUTES).build())
    }

    suspend fun publish(context: Context, database: AppDatabase): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val app = context.applicationContext
                try {
                    val uri = own(app) ?: return@withLock true
                    requireGrant(app, uri, write = true)
                    recoverPending(app, uri)
                    publishLocked(app, database)
                    published(app)
                    true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    error(app, failure)
                    false
                }
            }
        }

    private suspend fun publishLocked(context: Context, database: AppDatabase) {
        val uri = own(context) ?: return
        val id = deviceId(context)
        val previous = read(context, uri, allowEmpty = true)
        check(previous == null || previous.deviceId == id) {
            "No se modificará un archivo de otro dispositivo."
        }
        val local = database.withTransaction {
            val books = database.backupDao().booksAll().associateBy { it.id }
            database.backupDao().positionsAll().mapNotNull { position ->
                val book = books[position.bookId] ?: return@mapNotNull null
                ReadingPositionRecord(book.contentHash, position.locator,
                    position.progress, position.updatedAt, id)
            }
        }
        val deletions = database.backupDao().deletedBooksAll().associate { it.contentHash to it.deletedAt }
        val merged = ReadingPositionFileCodec.newest(
            (previous?.positions.orEmpty() + local).filter { it.updatedAt > (deletions[it.contentHash] ?: Long.MIN_VALUE) }
        )
        val newDoc = ReadingPositionDocument(id, merged.values.toList())
        val output = ReadingPositionFileCodec.encode(newDoc)
        val priorText = previous?.let(ReadingPositionFileCodec::encode)
        if (output == priorText) return
        writeVerified(context, uri, output, priorText)
    }

    private fun readPeer(context: Context, uri: Uri, seen: Set<String>): ReadingPositionDocument {
        requireGrant(context, uri, write = false)
        require(uri != own(context))
        fun validate(document: ReadingPositionDocument?): ReadingPositionDocument {
            val data = requireNotNull(document) { "Archivo de posiciones vacío." }
            require(data.deviceId != deviceId(context) && data.deviceId !in seen) {
                "El archivo recibido pertenece a este dispositivo o a otro ya vinculado."
            }
            return data
        }
        return try { validate(read(context, uri, false)) }
        catch (e: Exception) { validate(read(context, uri, false, alternate = true)) }
    }

    private fun read(context: Context, uri: Uri, allowEmpty: Boolean, alternate: Boolean = false): ReadingPositionDocument? {
        val stream = if (alternate) context.contentResolver
            .openTypedAssetFileDescriptor(uri, "application/json", null)?.createInputStream()
            else context.contentResolver.openInputStream(uri)
        val data = stream?.use { input ->
            val result = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(chunk)
                if (count == -1) break
                if (count == 0) continue
                require(result.size() <= ReadingPositionFileCodec.MAX_BYTES - count) {
                    "Archivo de posiciones demasiado grande."
                }
                result.write(chunk, 0, count)
            }
            result.toByteArray()
        } ?: throw IOException("El archivo de sincronización no se puede leer.")
        if (data.isEmpty() && allowEmpty) return null
        return ReadingPositionFileCodec.decode(String(data, Charsets.UTF_8))
    }

    private fun emergency(context: Context, uri: Uri) =
        File(context.filesDir, "reading-position-sync-rollback-" +
            SharedDocumentStore.hash(uri.toString().toByteArray()).take(24) + ".json")

    private fun recoverPending(context: Context, uri: Uri) {
        val backup = emergency(context, uri)
        // Older versions kept a single rollback. It is only eligible for
        // the currently registered own URI, never for a newly selected file.
        val legacy = File(context.filesDir, "reading-position-sync-rollback.json")
        if (!backup.exists() && legacy.isFile && uri == own(context))
            check(legacy.renameTo(backup))
        if (!backup.isFile) return
        // Only our own URI can be used for recovery. Never touch a peer.
        check(uri == own(context)) { "Destino de recuperación distinto del archivo propio." }
        val previous = backup.readText(Charsets.UTF_8)
        ReadingPositionFileCodec.decode(previous).also {
            check(it.deviceId == deviceId(context)) { "La copia de emergencia es de otro dispositivo." }
        }
        val existing = runCatching { read(context, uri, allowEmpty = true) }.getOrNull()
        require(existing == null || existing.deviceId == deviceId(context)) {
            "No se recuperará sobre un archivo de otro dispositivo."
        }
        if (existing != null) {
            // Valid current data already exists. It could contain newer positions.
            // Do not roll it back; preserve the local recovery as a manual safeguard.
            check(backup.delete()) { "No se puede limpiar recuperación de posiciones." }
        } else {
            writeRaw(context, uri, previous)
            check(read(context, uri, allowEmpty = false)?.deviceId == deviceId(context)) {
                "No se pudo recuperar el documento de posiciones."
            }
            check(backup.delete()) { "No se puede limpiar recuperación de posiciones." }
        }
    }

    private fun writeVerified(context: Context, uri: Uri, content: String, previous: String?) {
        // A private rollback prevents a provider failure from discarding the prior
        // known-good state. The user's local Room positions are never deleted.
        val backup = emergency(context, uri)
        require(!backup.exists()) { "Primero hay que recuperar el archivo anterior." }
        if (previous != null) backup.writeText(previous, Charsets.UTF_8)
        try {
            writeRaw(context, uri, content)
            val actual = read(context, uri, allowEmpty = false)
            check(actual != null &&
                ReadingPositionFileCodec.encode(actual) == content) {
                "Drive no devolvió el contenido guardado; sincronización sin verificar."
            }
            if (backup.exists()) check(backup.delete()) {
                "No se pudo limpiar la copia de emergencia de posiciones."
            }
        } catch (failure: Exception) {
            if (backup.isFile) {
                runCatching {
                    writeRaw(context, uri, backup.readText(Charsets.UTF_8))
                    check(read(context, uri, allowEmpty = false) != null)
                    check(backup.delete())
                }
            }
            throw failure
        }
    }

    private fun writeRaw(context: Context, uri: Uri, text: String) {
        context.contentResolver.openOutputStream(uri, "wt")?.use {
            it.write(text.toByteArray(Charsets.UTF_8))
            it.flush()
        } ?: throw IOException("El proveedor no permite sobrescribir el archivo propio.")
    }

    private fun success(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(LAST_SYNC, System.currentTimeMillis()).putString(LAST_ERROR, "").apply()
        changes.value += 1
    }

    private fun published(context: Context) {
        prefs(context).edit().putLong(LAST_PUBLISHED, System.currentTimeMillis()).apply()
        changes.value += 1
    }

    private fun error(context: Context, failure: Exception) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LAST_ERROR,
                failure.message ?: "No se ha podido comprobar el estado de otros dispositivos.")
            .apply()
        changes.value += 1
    }
}

class ReadingPositionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext
        if (!ReadingPositionSafSync.hasOwn(app)) return Result.success()
        val revision = ReadingPositionSafSync.revision(app)
        val ok = ReadingPositionSafSync.publish(app, AppDatabase.get(app))
        return if (ok && revision == ReadingPositionSafSync.revision(app)) Result.success() else Result.retry()
    }
}
