package com.javierrb35.bibliotecaia.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

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
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    archive.inputStream().use { source -> source.copyTo(output, 64 * 1024) }
                    output.flush()
                } ?: throw IllegalArgumentException("La ubicación elegida no permite escribir.")
            } catch (e: Exception) {
                // A failed SAF stream can leave an incomplete document at the destination.
                // Best effort deletion; provider might not support delete.
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                throw e
            }
            ExportResult(books.size, bytes)
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
            val fromArchive = BackupCodec.fromJson(content.json).books
            val existingByHash = database.backupDao().booksAll().associateBy { it.contentHash }
            val finalPaths = linkedMapOf<String, String>()
            val booksBase = File(context.filesDir, "books").canonicalFile

            for (incoming in fromArchive) {
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
                val destination = File(dir, local.fileName).canonicalFile
                require(destination.parentFile == dir) { "Nombre de archivo inseguro." }
                if (destination.exists()) {
                    require(destination.isFile &&
                        OriginalLinker(context.filesDir).sha256Hex(destination) == incoming.contentHash) {
                        "Ya hay un archivo distinto en la ubicación del libro. No se sobrescribe."
                    }
                } else {
                    val staged = content.staged[CompleteBackupArchive.entryName(incoming.id)]
                        ?: throw IllegalArgumentException("Falta un original verificado.")
                    val tmp = File.createTempFile(".restore-", ".tmp", dir)
                    try {
                        staged.inputStream().use { source ->
                            tmp.outputStream().use { sink -> source.copyTo(sink, 64 * 1024) }
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
