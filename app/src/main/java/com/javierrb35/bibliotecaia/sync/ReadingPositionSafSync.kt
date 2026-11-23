package com.javierrb35.bibliotecaia.sync

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.ReadingPositionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingPublish: Job? = null

    data class Status(val ownConfigured: Boolean, val peerCount: Int,
                      val lastSuccess: Long, val lastError: String)

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
        return Status(prefs.getString(OWN, null) != null, peers(context).size,
            prefs.getLong(LAST_SYNC, 0L), prefs.getString(LAST_ERROR, "").orEmpty())
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
        check(context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && (!write || it.isWritePermission)
        }) { "Sin permiso persistente para ${if (write) "leer y escribir" else "leer"} el archivo." }
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
                val current = read(app, uri, allowEmpty = true)
                val id = deviceId(app)
                require(current == null || current.deviceId == id) {
                    "Este archivo pertenece a otro dispositivo. Vincúlalo como archivo de lectura."
                }
                check(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(OWN, uri.toString()).putString(LAST_ERROR, "").commit()) {
                    "No se pudo registrar el archivo propio."
                }
                publishLocked(app, database)
            }
        }

    /** Peer files are always opened read-only. Never write to a peer URI. */
    suspend fun addPeer(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val app = context.applicationContext
            requireGrant(app, uri, write = false)
            require(uri != own(app)) { "Tu archivo propio no es otro dispositivo." }
            val data = requireNotNull(read(app, uri, allowEmpty = false)) {
                "El otro dispositivo todavía no ha creado su archivo de posiciones."
            }
            require(data.deviceId != deviceId(app)) {
                "Ese archivo también pertenece a este dispositivo."
            }
            val all = (peers(app) + uri).distinct()
            check(all.size <= 10) { "Se ha alcanzado el límite de dispositivos vinculados." }
            check(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PEERS, JSONArray(all.map(Uri::toString)).toString())
                .putString(LAST_ERROR, "").commit()) {
                "No se pudo registrar el otro dispositivo."
            }
        }
    }

    fun unlinkPeers(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(PEERS).apply()
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
                if (own(app) == null) return@withLock true
                try {
                    val ownUri = requireNotNull(own(app))
                    requireGrant(app, ownUri, write = true)
                    recoverPending(app, ownUri)
                    val id = deviceId(app)
                    val ownData = read(app, ownUri, allowEmpty = true)
                    check(ownData == null || ownData.deviceId == id) {
                        "El archivo propio fue sustituido por el de otro dispositivo."
                    }
                    val peerData = peers(app).map { peerUri ->
                        requireGrant(app, peerUri, write = false)
                        requireNotNull(read(app, peerUri, allowEmpty = false)) {
                            "Hay un dispositivo vinculado con archivo vacío."
                        }
                    }
                    val documents = listOfNotNull(ownData) + peerData
                    check(documents.map { it.deviceId }.distinct().size == documents.size) {
                        "Hay dos archivos vinculados para el mismo dispositivo."
                    }
                    val latest = ReadingPositionFileCodec.newest(documents.flatMap { it.positions })
                    database.withTransaction {
                        val books = database.backupDao().booksAll().associateBy { it.contentHash }
                        for ((hash, incoming) in latest) {
                            val book = books[hash] ?: continue
                            val existing = database.backupDao().positionFor(book.id)
                            // A user moving backwards is valid; never compare progress itself.
                            if (existing == null || incoming.updatedAt > existing.updatedAt) {
                                database.backupDao().insertPositionReplace(
                                    ReadingPositionEntity(book.id, incoming.locator,
                                        incoming.progress, incoming.updatedAt))
                                database.backupDao().updateBookProgressAfterRestore(
                                    book.id, incoming.progress)
                            }
                        }
                    }
                    publishLocked(app, database)
                    success(app)
                    true
                } catch (failure: Exception) {
                    error(app, failure)
                    false
                }
            }
        }

    /**
     * Position writes from a reader publish to this device's OWN file after
     * a short debounce. They do not pull another position into an open reader.
     */
    fun requestPublish(context: Context) {
        val app = context.applicationContext
        if (own(app) == null) return
        pendingPublish?.cancel()
        pendingPublish = scope.launch {
            delay(3000)
            try {
                publish(app, AppDatabase.get(app))
            } catch (e: Exception) {
                error(app, e)
            }
        }
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
                    success(app)
                    true
                } catch (failure: Exception) {
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
        val books = database.backupDao().booksAll().associateBy { it.id }
        val local = database.backupDao().positionsAll().mapNotNull { position ->
            val book = books[position.bookId] ?: return@mapNotNull null
            ReadingPositionRecord(book.contentHash, position.locator,
                position.progress, position.updatedAt, id)
        }
        val merged = ReadingPositionFileCodec.newest(
            previous?.positions.orEmpty() + local
        )
        val newDoc = ReadingPositionDocument(id, merged.values.toList())
        val output = ReadingPositionFileCodec.encode(newDoc)
        val priorText = previous?.let(ReadingPositionFileCodec::encode)
        if (output == priorText) return
        writeVerified(context, uri, output, priorText)
    }

    private fun read(context: Context, uri: Uri, allowEmpty: Boolean): ReadingPositionDocument? {
        val data = context.contentResolver.openInputStream(uri)?.use { input ->
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

    private fun emergency(context: Context) =
        File(context.filesDir, "reading-position-sync-rollback.json")

    private fun recoverPending(context: Context, uri: Uri) {
        val backup = emergency(context)
        if (!backup.isFile) return
        // Only our own URI can be used for recovery. Never touch a peer.
        check(uri == own(context)) { "Destino de recuperación distinto del archivo propio." }
        val previous = backup.readText(Charsets.UTF_8)
        ReadingPositionFileCodec.decode(previous).also {
            check(it.deviceId == deviceId(context)) { "La copia de emergencia es de otro dispositivo." }
        }
        val existing = runCatching { read(context, uri, allowEmpty = true) }.getOrNull()
        if (existing != null && existing.deviceId == deviceId(context)) {
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
        val backup = emergency(context)
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
    }

    private fun error(context: Context, failure: Exception) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LAST_ERROR,
                failure.message ?: "No se ha podido comprobar el estado de otros dispositivos.")
            .apply()
    }
}
