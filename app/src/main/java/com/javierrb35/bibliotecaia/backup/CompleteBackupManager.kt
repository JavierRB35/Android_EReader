package com.javierrb35.bibliotecaia.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Full, user-initiated, portable backup through Android's SAF document picker.
 * The user controls the destination (Drive, local storage, another provider).
 * No account or OAuth is required by this application.
 */
class CompleteBackupManager(
    private val context: Context,
    private val database: AppDatabase,
    private val appVersion: String
) {
    data class ExportResult(val books: Int, val originalBytes: Long, val catalogueHashes: Set<String> = emptySet(),
                            val missingTitles: List<String> = emptyList())
    data class ImportResult(val report: RestoreReport, val originals: Int, val originalBytes: Long, val catalogueHashes: Set<String> = emptySet(),
                            val missingTitles: List<String> = emptyList())

    /**
     * Replace the same SAF document after fully constructing the ZIP locally.
     * For a pre-existing document, a private rollback copy is retained until
     * the provider's read-back matches the generated archive SHA-256.
     *
     * SAF providers do NOT guarantee an atomic replace: on process/device loss
     * during a remote write the user may need to recover the prior snapshot.
     */
    suspend fun exportTo(uri: Uri, replaceExisting: Boolean = false): ExportResult =
        withContext(Dispatchers.IO) {
            require(uri.scheme == "content") { "Elige un archivo mediante el selector Android." }
            require(!hasPendingRecovery()) {
                "Hay una copia anterior pendiente de recuperación. Recupérala antes de guardar otra."
            }
            val manager = BackupManager(database, appVersion)
            val json = manager.exportJson()
            // BackupCodec deliberately clears localPath for safe *restoration*.
            // Export must read the original private paths from Room instead.
            val described = BackupCodec.fromJson(json).books
            val books = database.backupDao().booksAll()
            require(books.size == described.size &&
                books.map { it.id to it.contentHash }.toSet() ==
                    described.map { it.id to it.contentHash }.toSet() &&
                books.associateBy { it.id }.all { (id, book) ->
                    described.any { it.id == id &&
                        it.fileName == book.fileName && it.sizeBytes == book.sizeBytes }
                }) {
                "La biblioteca cambió durante la preparación. Repite la copia."
            }
            val archive = File.createTempFile("biblioteca-completa-", ".zip", context.cacheDir)
            val stage = File(context.cacheDir, "central-export-" + UUID.randomUUID())
            var writeStarted = false
            try {
                check(stage.mkdirs()) { "No se puede preparar la biblioteca central." }
                val missing = books.filter { book ->
                    book.localPath.isBlank() || !File(book.localPath).isFile
                }.map { it.contentHash }.toSet()
                // Read the exact previous snapshot which will be preserved for rollback.
                // Never replace a central catalogue with only a subset of its books.
                if (replaceExisting) safeguardPreviousCopy(uri)
                val previous = if (replaceExisting) recoveryFile.inputStream().use {
                    CompleteBackupArchive.read(it, stage, missing)
                } else null
                val priorBooks = previous?.let { prior ->
                    BackupCodec.fromJson(prior.json).books.filter { it.contentHash !in prior.missingHashes }
                }.orEmpty()
                require(priorBooks.all { prior -> books.any { it.contentHash == prior.contentHash } }) {
                    "Hay libros centrales que aún no están en este catálogo. Abre Biblioteca completa antes de guardar."
                }
                val byHash = priorBooks.associateBy { it.contentHash }
                val fromShared = books.filter { it.contentHash in missing && it.contentHash !in byHash }
                val sharedFiles = if (fromShared.isEmpty() ||
                    !com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(context)) emptyMap() else {
                    com.javierrb35.bibliotecaia.sync.SharedLibrary.stageOriginals(context, fromShared, stage, skipUnavailable = true)
                }
                val unavailable = mutableListOf<BookEntity>()
                val sources = books.mapNotNull { book ->
                    if (book.contentHash in missing) {
                        val remote = byHash[book.contentHash]
                        val staged = remote?.let { previous?.staged?.get(CompleteBackupArchive.entryName(it.id)) }
                        val source = staged ?: sharedFiles[book.contentHash]
                        if (source == null) {
                            unavailable.add(book)
                            null
                        } else book.copy(localPath = source.absolutePath)
                    } else {
                        val requiredDir = File(context.filesDir, "books/" + book.id).canonicalFile
                        val source = File(book.localPath).canonicalFile
                        require(source.parentFile == requiredDir && source.name == book.fileName) {
                            "No se puede leer el original interno: " + book.title.take(80)
                        }
                        book
                    }
                }
                val bytes = archive.outputStream().use { output ->
                    val backupJson = if (unavailable.isEmpty()) json else
                        CompleteBackupArchive.withMissingOriginals(json, unavailable.map { it.contentHash }.toSet())
                    CompleteBackupArchive.write(output, backupJson, sources)
                }
                if (replaceExisting) {
                    val currentHash = context.contentResolver.openInputStream(uri)?.use(::sha256)
                    require(currentHash == sha256(recoveryFile.inputStream())) {
                        "La biblioteca central ha cambiado durante la preparación. No se sobrescribirá."
                    }
                }
                try {
                    writeStarted = true
                    overwriteAndVerify(uri, archive)
                    if (replaceExisting) clearRecovery()
                } catch (e: Exception) {
                    if (replaceExisting) {
                        val recovered = runCatching { recoverPreviousCopyInternal(uri) }.isSuccess
                        if (recovered) {
                            throw IOException(
                                "No se pudo actualizar la copia. Se ha restaurado el archivo anterior.",
                                e
                            )
                        }
                        throw IOException(
                            "La actualización falló. La copia anterior sigue guardada dentro de " +
                                "la aplicación; usa «Recuperar copia anterior» en Ajustes.",
                            e
                        )
                    }
                    throw e
                }
                ExportResult(sources.size, bytes, sources.map { it.contentHash }.toSet(), unavailable.map { it.title })
            } catch (e: Exception) {
                // Preparation failures occur before any write. Release only the
                // rollback snapshot that this attempt created; retain failed-write recovery.
                if (replaceExisting && hasPendingRecovery() && !writeStarted) clearRecovery()
                // A newly CREATED SAF document is safe to delete if its first write
                // fails. Never delete a pre-existing backup selected for replacement.
                if (!replaceExisting) {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                }
                throw e
            } finally {
                archive.delete()
                stage.deleteRecursively()
            }
        }

    private val recoveryFile: File
        get() = File(context.filesDir, "previous-complete-backup.zip")

    private val recoveryPreferences
        get() = context.getSharedPreferences("biblioteca_backup_recovery", Context.MODE_PRIVATE)

    /** The prior valid document is preserved if a provider cannot roll back a failed write. */
    fun hasPendingRecovery(): Boolean =
        recoveryFile.isFile && !recoveryPreferences.getString("uri", null).isNullOrBlank()

    private fun safeguardPreviousCopy(uri: Uri) {
        require(!recoveryFile.exists()) {
            "Existe una copia de emergencia anterior; recupérala antes de continuar."
        }
        val staged = File.createTempFile("backup-previous-", ".tmp", context.filesDir)
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("El archivo elegido no se puede leer.")
            input.use { source ->
                staged.outputStream().use { sink -> source.copyTo(sink, 64 * 1024) }
            }
            require(staged.length() > 0L) { "La copia existente está vacía o inaccesible." }
            require(staged.renameTo(recoveryFile)) {
                "No se puede preservar la copia anterior. No se sobrescribirá."
            }
            check(recoveryPreferences.edit().putString("uri", uri.toString()).commit()) {
                "No se pudo registrar la copia de emergencia."
            }
        } catch (e: Exception) {
            staged.delete()
            // No write to the selected backup has happened yet.
            if (recoveryPreferences.getString("uri", null) != uri.toString()) {
                recoveryFile.delete()
            }
            if (e is SecurityException || e is IOException || e.message.orEmpty().contains("io_error", ignoreCase = true)) {
                throw IOException("No se puede leer la copia anterior desde el proveedor. Comprueba Drive y la conexión, o elige un archivo NUEVO de respaldo. No se ha sobrescrito la copia anterior.", e)
            }
            throw e
        } finally {
            staged.delete()
        }
    }

    private fun overwriteAndVerify(uri: Uri, file: File) {
        val expected = sha256(file.inputStream())
        // "w" may APPEND on certain providers; explicitly request truncation.
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("El proveedor no permite sobrescribir el archivo.")
        output.use { sink ->
            file.inputStream().use { source -> source.copyTo(sink, 64 * 1024) }
            sink.flush()
        }
        val actual = context.contentResolver.openInputStream(uri)?.use(::sha256)
            ?: throw IOException("No se puede verificar la copia escrita.")
        check(actual == expected) {
            "El archivo guardado no coincide con la copia generada."
        }
    }

    private fun sha256(source: java.io.InputStream): String = source.use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n > 0) digest.update(buffer, 0, n)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun clearRecovery() {
        if (recoveryFile.exists()) {
            check(recoveryFile.delete()) { "No se pudo limpiar la copia local temporal." }
        }
        recoveryPreferences.edit().remove("uri").apply()
    }

    private fun recoverPreviousCopyInternal(uri: Uri) {
        require(hasPendingRecovery() &&
            recoveryPreferences.getString("uri", null) == uri.toString()) {
            "No existe copia anterior para este archivo."
        }
        overwriteAndVerify(uri, recoveryFile)
        clearRecovery()
    }

    /** Retry a preserved rollback copy after a failed/interrupted provider write. */
    suspend fun recoverPreviousCopy() = withContext(Dispatchers.IO) {
        val text = recoveryPreferences.getString("uri", null)
            ?: throw IllegalArgumentException("No hay ninguna copia anterior por recuperar.")
        recoverPreviousCopyInternal(Uri.parse(text))
    }

    suspend fun restoreFrom(uri: Uri, wantedHashes: Set<String>? = null): ImportResult = withContext(Dispatchers.IO) {
        val stage = File(context.cacheDir, "restore-books-" + UUID.randomUUID()).apply {
            check(mkdirs()) { "No se puede preparar la restauración." }
        }
        val created = mutableListOf<File>()
        var committed = false
        try {
            val content = context.contentResolver.openInputStream(uri)?.use { input ->
                CompleteBackupArchive.read(input, stage, wantedHashes)
            } ?: throw IllegalArgumentException("No se puede leer el archivo de copia.")
            coroutineContext.ensureActive()
            val fromArchive = BackupCodec.fromJson(content.json).books
            val included = fromArchive.filter { it.contentHash !in content.missingHashes }
            require(wantedHashes == null || included.map { it.contentHash }.containsAll(wantedHashes)) {
                "El libro no está en esta biblioteca central."
            }
            val existingByHash = database.backupDao().booksAll().associateBy { it.contentHash }
            val finalPaths = linkedMapOf<String, String>()
            val booksBase = File(context.filesDir, "books").canonicalFile

            for (incoming in included) {
                if (wantedHashes != null && incoming.contentHash !in wantedHashes) continue
                coroutineContext.ensureActive()
                val local = existingByHash[incoming.contentHash] ?: incoming
                val already = File(local.localPath)
                if (local.localPath.isNotBlank() && already.isFile &&
                    OriginalLinker(context.filesDir).sha256Hex(already) == incoming.contentHash) {
                    finalPaths[incoming.contentHash] = already.absolutePath
                    continue
                }
                // Never trust a path embedded in the archive: its JSON decoder clears it.
                val dir = File(booksBase, local.id).canonicalFile
                require(dir.parentFile == booksBase && (dir.isDirectory || dir.mkdirs())) {
                    "Ruta de destino del libro inválida."
                }
                val originalDestination = File(dir, local.fileName).canonicalFile
                require(originalDestination.parentFile == dir) { "Nombre de archivo inseguro." }
                // A corrupt file may occupy the canonical path. Never overwrite or
                // delete it: materialize the verified original under a unique safe
                // name in the SAME private book directory, then relink Room.
                val validExisting = originalDestination.isFile &&
                    originalDestination.length() == incoming.sizeBytes &&
                    OriginalLinker(context.filesDir).sha256Hex(originalDestination) == incoming.contentHash
                val destination = if (originalDestination.exists() && !validExisting) {
                    val extension = incoming.format.name.lowercase(java.util.Locale.ROOT)
                    File(dir, "recuperado-${UUID.randomUUID()}.$extension").canonicalFile
                } else originalDestination
                require(destination.parentFile == dir && (!destination.exists() || validExisting)) {
                    "No hay una ubicación segura para recuperar este libro."
                }
                if (!validExisting) {
                    val staged = content.staged[CompleteBackupArchive.entryName(incoming.id)]
                        ?: throw IllegalArgumentException("Falta un original verificado.")
                    val tmp = File.createTempFile(".restore-", ".tmp", dir)
                    try {
                        staged.inputStream().use { source ->
                            tmp.outputStream().use { sink ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    coroutineContext.ensureActive()
                                    val count = source.read(buffer)
                                    if (count < 0) break
                                    if (count > 0) sink.write(buffer, 0, count)
                                }
                            }
                        }
                        require(tmp.length() == incoming.sizeBytes &&
                            OriginalLinker(context.filesDir).sha256Hex(tmp) == incoming.contentHash &&
                            tmp.renameTo(destination)) {
                            "No se pudo colocar un original completo en el almacenamiento local."
                        }
                        created.add(destination)
                    } finally {
                        tmp.delete()
                    }
                }
                finalPaths[incoming.contentHash] = destination.absolutePath
            }
            coroutineContext.ensureActive()
            // Room transaction restores metadata and file paths together.
            // Staged originals are verified/materialized before any DB mutation.
            val report = BackupManager(database, appVersion)
                .restoreJson(content.json, finalPaths)
            committed = true
            ImportResult(report, finalPaths.size, content.bytes, included.map { it.contentHash }.toSet(),
                fromArchive.filter { it.contentHash in content.missingHashes }.map { it.title })
        } finally {
            if (!committed) {
                // Only remove files created by this attempt, never preexisting originals.
                created.forEach { it.delete() }
            }
            stage.deleteRecursively()
        }
    }
}
