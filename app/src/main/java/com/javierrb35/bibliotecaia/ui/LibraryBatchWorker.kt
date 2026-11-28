package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.work.*
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.sync.SharedLibrary
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Only the named shared folder uses this persistent queue. WorkManager Data is
 * limited to 10 KB: keep the immutable selection in private app storage, not
 * in the request. A single chain serializes batches, including across restarts.
 * Domain failures are recorded as terminal results, not failed dependencies.
 */
internal object LibraryBatchQueue {
    const val TAG = "library-batch-v1"
    const val ACTION = "batch-action:"
    const val ORDER = "batch-order:"
    const val TOTAL = "batch-total:"
    data class Entry(val id: String, val hash: String, val path: String,
                     val title: String = "", val fileName: String = "", val format: String = "",
                     val size: Long = -1, val addedAt: Long = -1) {
        fun book(): BookEntity = BookEntity(id, title, null,
            com.javierrb35.bibliotecaia.data.BookFormat.valueOf(format),
            fileName, path, hash, size, addedAt)
    }
    data class Request(val action: LibraryBatchAction, val uri: String, val books: List<Entry>) {
        fun encode(): String = JSONObject().put("action", action.name).put("uri", uri)
            .put("books", JSONArray(books.map {
                JSONObject().put("id", it.id).put("hash", it.hash).put("path", it.path)
                    .put("title", it.title).put("fileName", it.fileName).put("format", it.format)
                    .put("size", it.size).put("addedAt", it.addedAt)
            })).toString()
        companion object {
            fun decode(raw: String): Request {
                val json = JSONObject(raw)
                val books = json.getJSONArray("books")
                return Request(LibraryBatchAction.valueOf(json.getString("action")),
                    json.getString("uri"), (0 until books.length()).map {
                        val item = books.getJSONObject(it)
                        Entry(item.getString("id"), item.getString("hash"), item.getString("path"),
                            item.optString("title"), item.optString("fileName"), item.optString("format"),
                            item.optLong("size", -1), item.optLong("addedAt", -1))
                    })
            }
        }
    }
    private fun folder(context: Context) = File(context.filesDir, "library-batches").apply {
        if (!isDirectory) mkdirs()
        check(isDirectory) { "No se pudo preparar la cola." }
    }
    private fun requestFile(context: Context, id: UUID) = File(folder(context), "$id.json")
    private fun stopFile(context: Context, id: UUID) = File(folder(context), "$id.stop")
    fun stopped(context: Context, id: UUID) = stopFile(context, id).exists()
    fun stop(context: Context, id: UUID) { stopFile(context, id).createNewFile() }
    fun read(context: Context, id: UUID) = Request.decode(
        AtomicFile(requestFile(context, id)).readFully().toString(Charsets.UTF_8))
    private fun deletionMarker(context: Context, id: UUID, hash: String, phase: String): File {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        return File(folder(context), "$id-$hash.$phase")
    }
    fun deletionStarted(context: Context, id: UUID, hash: String) =
        deletionMarker(context, id, hash, "started").isFile
    fun deletionDone(context: Context, id: UUID, hash: String) =
        deletionMarker(context, id, hash, "done").isFile
    fun markDeletion(context: Context, id: UUID, hash: String, phase: String) {
        val file = AtomicFile(deletionMarker(context, id, hash, phase))
        val out = file.startWrite()
        try { out.write(1); file.finishWrite(out) }
        catch (e: Exception) { file.failWrite(out); throw e }
    }
    fun clean(context: Context, id: UUID) {
        AtomicFile(requestFile(context, id)).delete()
        stopFile(context, id).delete()
        folder(context).listFiles()?.filter { it.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    suspend fun enqueue(context: Context, action: LibraryBatchAction, uri: Uri,
                        books: List<BookEntity>): UUID = withContext(Dispatchers.IO + NonCancellable) {
        val app = context.applicationContext
        require(books.isNotEmpty())
        val snapshot = Request(action, uri.toString(),
            books.distinctBy { it.id }.map { Entry(it.id, it.contentHash, it.localPath, it.title, it.fileName,
                it.format.name, it.sizeBytes, it.addedAt) })
        val work = OneTimeWorkRequestBuilder<LibraryBatchWorker>()
            .addTag(TAG).addTag(ACTION + action.name)
            .addTag(TOTAL + snapshot.books.size)
            .addTag(ORDER + System.currentTimeMillis())
            .keepResultsForAtLeast(1, java.util.concurrent.TimeUnit.DAYS)
            .build()
        val file = AtomicFile(requestFile(app, work.id))
        val stream = file.startWrite()
        try {
            stream.write(snapshot.encode().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
        // Do not delete the request on an uncertain enqueue result: the
        // database may already contain the work. Retention is local only.
        WorkManager.getInstance(app).enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
            .result.get()
        work.id
    }

    fun label(info: WorkInfo): String = info.tags.firstOrNull { it.startsWith(ACTION) }
        ?.removePrefix(ACTION)?.let { runCatching { LibraryBatchAction.valueOf(it).label }.getOrNull() }
        ?: "Operación de biblioteca"
    fun order(info: WorkInfo) = info.tags.firstOrNull { it.startsWith(ORDER) }
        ?.removePrefix(ORDER)?.toLongOrNull() ?: 0L
    fun total(info: WorkInfo) = info.tags.firstOrNull { it.startsWith(TOTAL) }
        ?.removePrefix(TOTAL)?.toIntOrNull() ?: 0
}

class LibraryBatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        var total = 0
        var completed = 0
        var failures = emptyList<String>()
        fun stopped() = LibraryBatchQueue.stopped(app, id)
        suspend fun progress(done: Int, phase: String) {
            completed = done
            setProgress(workDataOf("done" to done, "phase" to phase.take(180)))
        }
        try {
            val request = LibraryBatchQueue.read(app, id)
            total = request.books.size
            val expected = Uri.parse(request.uri)
            if (request.action == LibraryBatchAction.DELETE) {
                val db = AppDatabase.get(app)
                for (entry in request.books) {
                    if (LibraryBatchQueue.deletionDone(app, id, entry.hash)) {
                        completed++
                        continue
                    }
                    val started = LibraryBatchQueue.deletionStarted(app, id, entry.hash)
                    if (stopped() && !started) continue
                    progress(completed, "Eliminando: " + entry.title.take(100))
                    try {
                        SharedLibrary.deletePermanent(app, db, expected, entry.book()) {
                            LibraryBatchQueue.markDeletion(app, id, entry.hash, "started")
                        }
                        LibraryBatchQueue.markDeletion(app, id, entry.hash, "done")
                        completed++
                    } catch (e: CancellationException) { throw e }
                    catch (e: SharedLibrary.StaleDeletionSelection) {
                        failures = failures + (entry.title + ": " + e.message)
                    } catch (e: Exception) {
                        if (LibraryBatchQueue.deletionStarted(app, id, entry.hash)) {
                            progress(completed, "Baja pendiente de confirmar en Drive; se reintentará.")
                            return@withContext Result.retry()
                        }
                        failures = failures + (entry.title + ": " + (e.message ?: "No se pudo eliminar."))
                    }
                }
            } else if (!stopped()) {
                require(SharedLibrary.namedFolderMode(app) && SharedLibrary.uri(app) == expected) {
                    "La biblioteca ha cambiado; vuelve a seleccionar los libros."
                }
                val db = AppDatabase.get(app)
                val books = mutableListOf<BookEntity>()
                val invalid = mutableListOf<String>()
                for (entry in request.books) {
                    val current = db.bookDao().findById(entry.id)
                    when {
                        current == null || current.contentHash != entry.hash ->
                            invalid.add("El libro seleccionado ha cambiado.")
                        request.action == LibraryBatchAction.REMOVE && current.localPath.isBlank() ->
                            completed++ // already retired on a previous attempt
                        request.action == LibraryBatchAction.REMOVE && current.localPath != entry.path ->
                            invalid.add(current.title.take(80) + ": la copia local ha cambiado.")
                        else -> books.add(current)
                    }
                }
                failures = invalid
                require(request.action != LibraryBatchAction.REMOVE || invalid.isEmpty()) {
                    "La selección ha cambiado. No se retirará ninguna otra copia del lote."
                }
                val previous = completed
                progress(previous, "Comprobando la selección…")
                if (books.isNotEmpty() && !stopped()) when (request.action) {
                    LibraryBatchAction.REMOVE -> {
                        // Preserve all-selected verification before the first deletion.
                        SharedLibrary.evictSelection(app, db, expected, books,
                            onProgress = { done, _ -> progress(previous + done, "Retirando copias verificadas…") },
                            cancelled = ::stopped)
                    }
                    LibraryBatchAction.SAVE -> {
                        val result = SharedLibrary.publishImports(app, db, expected, books.map { it.contentHash }.toSet())
                        failures = failures + result.errors
                        if (result.errors.isEmpty()) completed += result.verified
                    }
                    LibraryBatchAction.DELETE -> error("La baja requiere el flujo de eliminación verificada.")
                    LibraryBatchAction.DOWNLOAD -> supervisorScope {
                        // Stop an in-flight remote download without cancelling the
                        // unique chain or poisoning subsequent queued batches.
                        val task = async {
                            SharedLibrary.downloadBatch(app, db, books.map { it.contentHash }, expected) { done, _, title ->
                                setProgress(workDataOf("done" to (previous + done), "phase" to if (title.isBlank()) "Descargando…" else title.take(180)))
                            }
                        }
                        val watcher = launch {
                            while (task.isActive) {
                                if (stopped()) { task.cancel(); break }
                                delay(200)
                            }
                        }
                        try {
                            val result = task.await()
                            completed = previous + result.completed
                            failures = failures + result.failures
                            // Catalogar automáticamente los libros recién descargados (#142).
                            if (result.completed > 0 && com.javierrb35.bibliotecaia.catalog
                                    .AutoCatalogSettings.newBooksEnabled(app)) {
                                try {
                                    com.javierrb35.bibliotecaia.catalog.AutoCatalogQueue.enqueue(
                                        app, all = false, ids = books.map { it.id })
                                } catch (_: Exception) { /* la descarga ya es correcta */ }
                            }
                        } catch (e: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            if (!stopped()) throw e
                            // The worker's progress counts processed items, not
                            // successes; inspect the verified local downloads.
                            completed = previous + books.count { book ->
                                db.bookDao().findById(book.id)?.let {
                                    it.contentHash == book.contentHash && it.localPath.isNotBlank() &&
                                        File(it.localPath).isFile &&
                                        com.javierrb35.bibliotecaia.backup.OriginalLinker(app.filesDir)
                                            .sha256Hex(File(it.localPath)) == book.contentHash
                                } == true
                            }
                        } finally { watcher.cancel() }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            failures = failures + (e.message ?: "No se pudo completar la operación.")
        }
        val summary = buildString {
            append(completed).append(" de ").append(total).append(" libros completados.")
            if (completed < total) append(" ").append(total - completed).append(" sin completar; puedes reintentarlos.")
            failures.take(5).forEach { append("\n").append(it.take(180)) }
        }
        Result.success(workDataOf("summary" to summary.take(1800), "done" to completed))
    }
}
