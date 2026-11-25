package com.javierrb35.bibliotecaia.backup

import android.content.Context
import android.net.Uri
import com.javierrb35.bibliotecaia.data.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Scriptura-style workflow: one SAF document, visible status, manual creation,
 * and a non-destructive on-launch refresh ONLY when the library has changed.
 *
 * All manual, automatic and restoration operations are serialised. The original
 * ZIP's read-back/rollback guarantees remain owned by CompleteBackupManager.
 * No Drive OAuth, automatic remote login, or credentials are needed.
 */
object BackupRoutine {
    private const val PREFS = "biblioteca_backup_destination"
    private const val TARGET_URI = "uri"
    private const val FINGERPRINT = "fingerprint"
    private const val LAST_SUCCESS = "last_success"
    private const val LAST_ERROR = "last_error"
    private const val CENTRAL_READ_URI = "central_read_uri"
    private const val SHARED_AUTO_URI = "shared_auto_backup_uri"
    private const val DELETION_FINGERPRINT = "backup_deletions"
    private const val SHARED_INTERVAL_MS = 12L * 60 * 60 * 1000
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Status(
        val uri: Uri?,
        val lastSuccess: Long,
        val lastError: String,
        val recoveryPending: Boolean,
        val missingTitles: List<String> = emptyList(),
    )
    enum class Result { SAVED, UNCHANGED, PARTIAL }

    fun status(context: Context): Status {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val uri = prefs.getString(TARGET_URI, null)?.let(Uri::parse)
            ?.takeIf { picked ->
                com.javierrb35.bibliotecaia.sync.StorageGrants.allows(context, picked, true)
            }
        return Status(
            uri = uri,
            lastSuccess = prefs.getLong(LAST_SUCCESS, 0),
            lastError = prefs.getString(LAST_ERROR, "").orEmpty(),
            missingTitles = prefs.getStringSet("missing_original_titles", emptySet()).orEmpty().sorted(),
            recoveryPending = File(context.filesDir, "previous-complete-backup.zip").isFile &&
                context.getSharedPreferences("biblioteca_backup_recovery", Context.MODE_PRIVATE)
                    .getString("uri", null) != null,
        )
    }

    fun savedDestination(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TARGET_URI, null)?.let(Uri::parse)

    /** Create or refresh one portable backup in the chosen location. Each
     * installation owns its own backup; never race another writer. */
    suspend fun prepareFolder(context: Context, database: AppDatabase, version: String, tree: Uri): Result {
        val app = context.applicationContext
        val grants = com.javierrb35.bibliotecaia.sync.StorageGrants
        val target = withContext(Dispatchers.IO) {
            lock.withLock {
                val items = com.javierrb35.bibliotecaia.sync.SharedDocumentStore(app, tree).use { it.list() }
                val old = savedDestination(app)
                val existingOwn = grants.listed(old, tree, items)
                val recoveryPrefs = app.getSharedPreferences("biblioteca_backup_recovery", Context.MODE_PRIVATE)
                val recoveryUri = recoveryPrefs.getString("uri", null)?.let(Uri::parse)
                if (recoveryUri != null && grants.belongs(recoveryUri, tree) &&
                    grants.listed(recoveryUri, tree, items) == null) {
                    grants.archiveRollback(File(app.filesDir, "previous-complete-backup.zip"))
                    check(recoveryPrefs.edit().remove("uri").commit())
                }
                require(!status(app).recoveryPending) { "Hay una copia anterior pendiente de recuperar." }
                if (old != null && grants.belongs(old, tree) && existingOwn == null) {
                    // Forget only references/status; the local library is intact.
                    check(app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                        .remove(TARGET_URI).remove(FINGERPRINT).remove(LAST_SUCCESS).remove(LAST_ERROR)
                        .remove(SHARED_AUTO_URI).remove(DELETION_FINGERPRINT)
                        .remove("missing_original_titles").remove("central_index_uri")
                        .remove("central_index_hashes").commit())
                }
                val name = "BibliotecaIA-copia-" +
                    com.javierrb35.bibliotecaia.sync.ReadingPositionSafSync.deviceId(app).take(8) + ".zip"
                val existing = items.filter { it.name == name }
                require(existing.size <= 1) { "Hay varias copias con el mismo nombre en la ubicación." }
                existingOwn?.uri ?: existing.singleOrNull()?.uri ?: grants.create(app, tree, "application/zip", name)
            }
        }
        // A previously created nonempty archive is verified by the existing
        // replacement routine. Empty documents are new and safe to initialize.
        val populated = withContext(Dispatchers.IO) {
            app.contentResolver.openInputStream(target)?.use { it.read() != -1 }
                ?: error("No se puede comprobar la copia.")
        }
        val result = save(app, database, version, true, target, replaceExisting = populated)
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(SHARED_AUTO_URI, target.toString()).apply()
        return result
    }

    /** A separately connected archive is read-only; the configured backup remains
     * the single writer. Never make a new device overwrite a shared ZIP. */
    fun centralUri(context: Context): Uri? {
        com.javierrb35.bibliotecaia.sync.SharedLibrary.uri(context)?.let { return it }
        val uri = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CENTRAL_READ_URI, null)?.let(Uri::parse)
        return status(context).uri ?: uri?.takeIf { chosen ->
            context.contentResolver.persistedUriPermissions.any { it.uri == chosen && it.isReadPermission }
        }
    }

    /** Null means the current archive has not yet been verified on this device.
     * Do not confuse a stale Room catalogue with verified central originals. */
    fun centralHashes(context: Context): Set<String>? {
        if (com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(context))
            return com.javierrb35.bibliotecaia.sync.SharedLibrary.hashes(context)
        val uri = centralUri(context) ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("central_index_uri", null) != uri.toString()) return null
        return prefs.getStringSet("central_index_hashes", null)?.toSet()
    }

    private fun rememberCentral(context: Context, uri: Uri, hashes: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("central_index_uri", uri.toString())
            .putStringSet("central_index_hashes", hashes.toSet()).apply()
    }

    suspend fun connectCentral(context: Context, database: AppDatabase, version: String, uri: Uri) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val result = CompleteBackupManager(context.applicationContext, database, version)
                    .restoreFrom(uri, emptySet())
                check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(CENTRAL_READ_URI, uri.toString()).commit())
                rememberCentral(context, uri, result.catalogueHashes)
            }
        }

    suspend fun refreshCentral(context: Context, database: AppDatabase, version: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(context)) {
                    com.javierrb35.bibliotecaia.sync.SharedLibrary.sync(context, database)
                    return@withLock
                }
                val uri = centralUri(context) ?: error("Conecta primero la biblioteca central.")
                val result = CompleteBackupManager(context.applicationContext, database, version)
                    .restoreFrom(uri, emptySet())
                rememberCentral(context, uri, result.catalogueHashes)
            }
        }

    suspend fun downloadBook(context: Context, database: AppDatabase, version: String, hash: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(context)) {
                    com.javierrb35.bibliotecaia.sync.SharedLibrary.download(context, database, hash)
                    return@withLock
                }
                val uri = centralUri(context) ?: error("Conecta primero la biblioteca central.")
                val result = CompleteBackupManager(context.applicationContext, database, version)
                    .restoreFrom(uri, setOf(hash))
                rememberCentral(context, uri, result.catalogueHashes)
            }
        }

    suspend fun evictBook(context: Context, database: AppDatabase, version: String, id: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                if (com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(context)) {
                    com.javierrb35.bibliotecaia.sync.SharedLibrary.evict(context, database, id)
                    return@withLock
                }
                val uri = centralUri(context) ?: error("Conecta primero la biblioteca central.")
                val book = database.bookDao().findById(id) ?: error("No se encuentra el libro.")
                val manager = CompleteBackupManager(context.applicationContext, database, version)
                // On the writer, save current metadata/notes and every original first.
                if (status(context).uri == uri &&
                    com.javierrb35.bibliotecaia.sync.ReadingPositionSafSync.status(context).peerCount == 0) {
                    manager.exportTo(uri, replaceExisting = true)
                }
                // Verify the entire archive and the requested hash without extracting
                // any originals. A missing, inaccessible or corrupt archive blocks eviction.
                val stage = File(context.cacheDir, "central-check-" + java.util.UUID.randomUUID())
                check(stage.mkdirs())
                try {
                    val result = context.contentResolver.openInputStream(uri)?.use {
                        CompleteBackupArchive.read(it, stage, emptySet())
                    } ?: error("No se puede leer la biblioteca central.")
                    val centralBooks = BackupCodec.fromJson(result.json).books.filter { it.contentHash !in result.missingHashes }
                    rememberCentral(context, uri, centralBooks.map { it.contentHash }.toSet())
                    require(centralBooks.any {
                        it.contentHash == book.contentHash && it.sizeBytes == book.sizeBytes
                    }) { "El original aún no está guardado en la biblioteca central." }
                } finally { stage.deleteRecursively() }
                val latest = database.bookDao().findById(id) ?: error("No se encuentra el libro.")
                require(latest.localPath == book.localPath && latest.contentHash == book.contentHash)
                com.javierrb35.bibliotecaia.importer.LocalBookCopyRemover.delete(context.filesDir, latest)
                check(database.bookDao().clearLocalPath(id, latest.localPath) == 1)
            }
        }

    /** Called only from the library entry screen or after importing an original. */
    fun refreshOnLibraryOpen(context: Context, version: String) {
        val app = context.applicationContext
        val state = status(app)
        if (state.uri == null) return
        val shared = com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (shared) {
            // Only a NEW independently created backup is eligible for auto-write.
            // A legacy ZIP may still be shared by other devices.
            if (prefs.getString(SHARED_AUTO_URI, null) != state.uri.toString()) return
        } else if (com.javierrb35.bibliotecaia.sync.ReadingPositionSafSync.status(app).peerCount > 0) return
        scope.launch {
            try {
                val database = AppDatabase.get(app)
                // A permanent deletion is not an ordinary reading update. Refresh
                // the owned portable copy without waiting for the 12-hour interval.
                if (shared && state.lastSuccess > 0 &&
                    System.currentTimeMillis() - state.lastSuccess < SHARED_INTERVAL_MS &&
                    deletionFingerprint(database) == prefs.getString(DELETION_FINGERPRINT, "")) return@launch
                save(app, database, version, force = false)
            } catch (_: Exception) {
                // The last error is persisted; users can inspect it in Ajustes.
                // A failed automatic refresh must never interrupt reading.
            }
        }
    }

    suspend fun save(
        context: Context,
        database: AppDatabase,
        version: String,
        force: Boolean,
        chosenUri: Uri? = null,
        replaceExisting: Boolean = true,
    ): Result = withContext(Dispatchers.IO) {
        lock.withLock {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val current = status(app)
            val uri = chosenUri ?: current.uri
                ?: throw IllegalStateException("Primero elige dónde guardar la copia.")
            // Do not auto-refresh into a different URI after the destination changed.
            if (chosenUri == null && current.uri != uri) {
                throw IllegalStateException("El destino de la copia ha cambiado.")
            }
            val manager = CompleteBackupManager(app, database, version)
            try {
                require(!com.javierrb35.bibliotecaia.sync.SharedLibrary.isLibraryDocument(app, uri)) {
                    "Este archivo pertenece a la biblioteca sincronizada. Crea un archivo de respaldo diferente."
                }
                check(!manager.hasPendingRecovery()) {
                    "Existe una copia anterior pendiente de recuperación. Recupérala desde Ajustes."
                }
                val readOnly = prefs.getString(CENTRAL_READ_URI, null)?.let(Uri::parse)
                require(readOnly != uri || current.uri == uri) {
                    "Esta biblioteca está conectada en modo lectura. No se sobrescribirá desde otro dispositivo."
                }
                val signature = libraryFingerprint(app, database, version)
                val deletedBefore = deletionFingerprint(database)
                if (!force && chosenUri == null &&
                    signature == prefs.getString(FINGERPRINT, null) &&
                    current.missingTitles.isEmpty() &&
                    prefs.getLong(LAST_SUCCESS, 0L) > 0L
                ) return@withLock Result.UNCHANGED

                val exported = manager.exportTo(uri, replaceExisting = replaceExisting)
                rememberCentral(app, uri, exported.catalogueHashes)
                // Re-read the fingerprint AFTER saving. A change arriving during
                // the export must be saved on the next launch, not silently omitted.
                val after = libraryFingerprint(app, database, version)
                val destinationEdit = prefs.edit()
                    .putString(TARGET_URI, uri.toString())
                if (chosenUri != null && !replaceExisting &&
                    com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(app)) {
                    destinationEdit.putString(SHARED_AUTO_URI, uri.toString())
                } else if (chosenUri != null && chosenUri != current.uri) {
                    destinationEdit.remove(SHARED_AUTO_URI)
                }
                check(destinationEdit
                    .putString(FINGERPRINT,
                        if (after == signature) signature else "")
                    .putString(DELETION_FINGERPRINT, deletedBefore)
                    .putLong(LAST_SUCCESS, System.currentTimeMillis())
                    .putString(LAST_ERROR, "")
                    .putStringSet("missing_original_titles", exported.missingTitles.toSet())
                    .commit()) {
                    "La copia se verificó, pero no se pudo guardar su configuración."
                }
                if (exported.missingTitles.isEmpty()) Result.SAVED else Result.PARTIAL
            } catch (error: Exception) {
                // A failed attempt must not change an already configured target.
                prefs.edit().putString(
                    LAST_ERROR, error.message ?: "No se ha podido guardar la copia."
                ).apply()
                throw error
            }
        }
    }

    suspend fun restore(context: Context, database: AppDatabase, version: String, uri: Uri):
        CompleteBackupManager.ImportResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val result = CompleteBackupManager(context.applicationContext, database, version)
                .restoreFrom(uri)
            // The restored state may differ; force comparison on next library entry.
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(FINGERPRINT).apply()
            result
        }
    }

    suspend fun recover(context: Context, database: AppDatabase, version: String) =
        withContext(Dispatchers.IO) {
            lock.withLock {
                CompleteBackupManager(context.applicationContext, database, version)
                    .recoverPreviousCopy()
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().remove(FINGERPRINT).putString(LAST_ERROR, "").apply()
            }
        }

    /**
     * Stable hash includes all books, notes, versions, positions, categories and
     * relationships from the portable JSON plus internal original file metadata.
     * Never includes any API keys or device-specific user credentials.
     */
    private suspend fun deletionFingerprint(database: AppDatabase): String =
        database.backupDao().deletedBooksAll().sortedBy { it.contentHash }
            .joinToString("|") { it.contentHash + ":" + it.deletedAt }
            .let { value -> if (value.isEmpty()) "" else MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) } }

    private suspend fun libraryFingerprint(
        context: Context, database: AppDatabase, version: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val json = BackupManager(database, version).exportJson(exportedAt = 0L)
        digest.update(json.toByteArray(Charsets.UTF_8))
        for (book in database.backupDao().booksAll().sortedBy { it.id }) {
            val file = File(book.localPath)
            digest.update(
                ("\n" + book.id + ":" + file.length() + ":" +
                    file.lastModified() + ":" + file.isFile).toByteArray(Charsets.UTF_8)
            )
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
