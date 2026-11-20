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
    data class ExportResult(val books: Int, val originalBytes: Long)
    data class ImportResult(val report: RestoreReport, val originals: Int, val originalBytes: Long)

    suspend fun exportTo(uri: Uri): ExportResult = withContext(Dispatchers.IO) {
        val manager = BackupManager(database, appVersion)
        val json = manager.exportJson()
        val books = BackupCodec.fromJson(json).books
        val archive = File.createTempFile("biblioteca-completa-", ".zip", context.cacheDir)
        try {
            // Fail closed on damaged/missing originals before writing to Drive/provider.
            for (book in books) {
                coroutineContext.ensureActive()
                val requiredDir = File(context.filesDir, "books/" + book.id).canonicalFile
                val source = File(book.localPath).canonicalFile
                require(source.parentFile == requiredDir && source.name == book.fileName) {
                    "No se puede leer el original interno: " + book.title.take(80)
                }
            }
            val bytes = archive.outputStream().use { output ->
                CompleteBackupArchive.write(output, json, books)
            }
            try {
                coroutineContext.ensureActive()
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    archive.inputStream().use { source ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count > 0) output.write(buffer, 0, count)
                        }
                    }
                    coroutineContext.ensureActive()
                    output.flush()
                } ?: throw IllegalArgumentException("La ubicación elegida no permite escribir.")
            } catch (e: Exception) {
                // A failed SAF stream can leave an incomplete document at the destination.
                // Best effort deletion; provider might not support delete.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                throw e
            }
            ExportResult(books.size, bytes)
        } catch (e: Exception) {
            // ACTION_CREATE_DOCUMENT has already allocated the destination URI.
            // If a source book is missing/corrupt or staging fails, do not leave
            // an apparently valid empty/partial copy in the chosen provider.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        } finally {
            archive.delete()
        }
    }

    suspend fun restoreFrom(uri: Uri): ImportResult = withContext(Dispatchers.IO) {
        val stage = File(context.cacheDir, "restore-books-" + UUID.randomUUID()).apply {
            check(mkdirs()) { "No se puede preparar la restauración." }
        }
        val created = mutableListOf<File>()
        var committed = false
        try {
            val content = context.contentResolver.openInputStream(uri)?.use { input ->
                CompleteBackupArchive.read(input, stage)
            } ?: throw IllegalArgumentException("No se puede leer el archivo de copia.")
            coroutineContext.ensureActive()
            val fromArchive = BackupCodec.fromJson(content.json).books
            val existingByHash = database.backupDao().booksAll().associateBy { it.contentHash }
            val finalPaths = linkedMapOf<String, String>()
            val booksBase = File(context.filesDir, "books").canonicalFile

            for (incoming in fromArchive) {
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
            ImportResult(report, finalPaths.size, content.bytes)
        } finally {
            if (!committed) {
                // Only remove files created by this attempt, never preexisting originals.
                created.forEach { it.delete() }
            }
            stage.deleteRecursively()
        }
    }
}
