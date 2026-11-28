package com.javierrb35.bibliotecaia.catalog

import android.content.Context
import android.util.AtomicFile
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.javierrb35.bibliotecaia.ai.AiClients
import com.javierrb35.bibliotecaia.ai.AiFallbackConsent
import com.javierrb35.bibliotecaia.ai.AiFallbackRouter
import com.javierrb35.bibliotecaia.ai.AiKeyStore
import com.javierrb35.bibliotecaia.ai.AiModelSettings
import com.javierrb35.bibliotecaia.ai.BookCatalog
import com.javierrb35.bibliotecaia.backup.CentralCatalogue
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookClassificationEntity
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.sync.SharedLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Cola persistente de catalogación automática (Issue #142). Igual que la cola
 * de operaciones de biblioteca: la selección inmutable vive en almacenamiento
 * privado (el Data de WorkManager está limitado a 10 KB) y una única cadena
 * serializa las peticiones, sobrevive a reinicios y se reanuda sola.
 */
internal object AutoCatalogQueue {
    const val TAG = "auto-catalog-v1"
    const val TOTAL = "auto-catalog-total:"
    const val ORDER = "auto-catalog-order:"
    const val LABEL = "Catalogación automática"

    data class Request(val all: Boolean, val ids: List<String>) {
        fun encode(): String = JSONObject()
            .put("all", all)
            .put("ids", JSONArray(ids))
            .toString()

        companion object {
            fun decode(raw: String): Request {
                val json = JSONObject(raw)
                val ids = json.getJSONArray("ids")
                return Request(json.getBoolean("all"), (0 until ids.length()).map { ids.getString(it) })
            }
        }
    }

    private fun folder(context: Context) = File(context.filesDir, "auto-catalog").apply {
        if (!isDirectory) mkdirs()
        check(isDirectory) { "No se pudo preparar la cola de catalogación." }
    }
    private fun requestFile(context: Context, id: UUID) = File(folder(context), "$id.json")
    private fun stopFile(context: Context, id: UUID) = File(folder(context), "$id.stop")
    fun stopped(context: Context, id: UUID) = stopFile(context, id).exists()
    fun stop(context: Context, id: UUID) { stopFile(context, id).createNewFile() }
    fun read(context: Context, id: UUID): Request = Request.decode(
        AtomicFile(requestFile(context, id)).readFully().toString(Charsets.UTF_8))
    fun clean(context: Context, id: UUID) {
        AtomicFile(requestFile(context, id)).delete()
        stopFile(context, id).delete()
    }

    suspend fun enqueue(context: Context, all: Boolean, ids: List<String>): UUID =
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            val app = context.applicationContext
            require(all || ids.isNotEmpty()) { "No hay libros seleccionados." }
            val work = OneTimeWorkRequestBuilder<AutoCatalogWorker>()
                .addTag(TAG)
                .addTag(TOTAL + (if (all) -1 else ids.size))
                .addTag(ORDER + System.currentTimeMillis())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .keepResultsForAtLeast(1, TimeUnit.DAYS)
                .build()
            val file = AtomicFile(requestFile(app, work.id))
            val stream = file.startWrite()
            try {
                stream.write(Request(all, ids.distinct()).encode().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Exception) {
                file.failWrite(stream)
                throw e
            }
            WorkManager.getInstance(app)
                .enqueueUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
                .result.get()
            work.id
        }

    fun order(info: androidx.work.WorkInfo): Long =
        info.tags.firstOrNull { it.startsWith(ORDER) }?.removePrefix(ORDER)?.toLongOrNull() ?: 0L

    fun total(info: androidx.work.WorkInfo): Int =
        info.tags.firstOrNull { it.startsWith(TOTAL) }?.removePrefix(TOTAL)?.toIntOrNull() ?: 0
}

/** Error de configuración IA que no merece más intentos en esta ejecución. */
private class AiConfigurationError(cause: Throwable) : Exception(cause.message, cause)

/** Avance de una invocación del worker. */
private data class Batch(
    val processed: Int, val skipped: Int, val failures: List<String>,
    val stopped: Boolean, val remaining: Boolean,
)

/**
 * Cataloga por lotes sin bloquear la aplicación: reutiliza la sesión de
 * lectura del almacén compartido, llama a la IA con el enrutado Gemini→Groq
 * existente y escribe solo huecos (contrato #142). Cancelable mediante
 * marcador, reanudable (las fichas ya clasificadas se omiten) e idempotente.
 */
class AutoCatalogWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        /** Tiempo máximo por invocación antes de reanudar en un reintento. */
        private const val DEADLINE_MS = 240_000L
        /** Máximo de fichas por invocación. */
        private const val BATCH_LIMIT = 24
        /** Fallos IA transitorios consecutivos tras los que se aplaza la IA. */
        private const val MAX_AI_FAILURES = 3
        private const val RETRY_PREFS = "auto_catalog_v1"
        private const val RETRY_BACKOFF_MS = 5 * 60_000L
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val db = AppDatabase.get(app)
        val request = AutoCatalogQueue.read(app, id)
        val books = scope(db, request)
        val total = books.size
        var processed = 0
        var skipped = 0
        var failures = emptyList<String>()
        var stopped = false
        var remaining = false
        fun stoppedNow() = AutoCatalogQueue.stopped(app, id)

        val ask = buildAsk(app)
        var aiDisabled = ask == null
        var transientAiFailures = 0
        val retry = app.getSharedPreferences(RETRY_PREFS, Context.MODE_PRIVATE)

        val localOnly: suspend (BookEntity, File) -> File? = { book, _ ->
            val file = File(book.localPath)
            if (CentralCatalogue.local(book) && file.isFile) file else null
        }

        suspend fun runBatch(originalFor: suspend (BookEntity, File) -> File?): Batch {
            val engine = AutoCatalogEngine(db)
            var done = 0
            var skip = 0
            val failed = mutableListOf<String>()
            val deadline = android.os.SystemClock.elapsedRealtime() + DEADLINE_MS
            for (book in books) {
                coroutineContext.ensureActive()
                if (stoppedNow()) return Batch(done, skip, failed, true, true)
                if (android.os.SystemClock.elapsedRealtime() >= deadline || done >= BATCH_LIMIT) {
                    return Batch(done, skip, failed, false, true)
                }
                // Las fichas ya clasificadas no se reprocesan (idempotencia).
                val row = db.classificationDao().byBook(book.id)
                if (row != null && row.status != BookClassificationEntity.STATUS_PENDING) {
                    skip++
                    continue
                }
                if (System.currentTimeMillis() < retry.getLong("retry:" + book.contentHash, 0L)) {
                    skip++
                    continue
                }
                setProgress(workDataOf(
                    "done" to done, "total" to books.size,
                    "phase" to book.title.take(180).ifBlank { book.fileName }))
                var temp: File? = null
                try {
                    val original = if (book.format.name == "PDF") null else {
                        val target = File.createTempFile("catalog-original-", ".tmp", app.cacheDir)
                        temp = target
                        originalFor(book, target)
                    }
                    val outcome = engine.process(book, original, if (aiDisabled) null else ask)
                    if (outcome == AutoCatalogEngine.Outcome.PROCESSED) {
                        done++
                        retry.edit().remove("retry:" + book.contentHash).apply()
                    } else if (outcome == AutoCatalogEngine.Outcome.SKIPPED) {
                        skip++
                    } else {
                        failed.add(book.title.take(100))
                        retry.edit().putLong("retry:" + book.contentHash,
                            System.currentTimeMillis() + RETRY_BACKOFF_MS).apply()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: AiConfigurationError) {
                    // La clave o el modelo no sirven: seguir sin IA en esta ejecución.
                    aiDisabled = true
                    failed.add(book.title.take(100) + ": IA no disponible (" + (e.message ?: "") + ")")
                } catch (e: Exception) {
                    // Cuota/red caída: aplazar este libro y, si se repite, la IA entera.
                    transientAiFailures++
                    failed.add(book.title.take(100) + ": " + (e.message ?: "No se pudo catalogar."))
                    retry.edit().putLong("retry:" + book.contentHash,
                        System.currentTimeMillis() + RETRY_BACKOFF_MS).apply()
                    if (transientAiFailures >= MAX_AI_FAILURES) aiDisabled = true
                } finally {
                    temp?.delete()
                }
            }
            return Batch(done, skip, failed, false, false)
        }

        val batch = SharedLibrary.withStoreSession(app) { originalFor -> runBatch(originalFor) }
            ?: runBatch(localOnly)
        processed = batch.processed
        skipped = batch.skipped
        failures = batch.failures
        stopped = batch.stopped
        remaining = batch.remaining

        // Publicar argumentos, categorías y etiquetas nuevos a los otros
        // dispositivos por el mecanismo de sincronización existente.
        if (processed > 0 && SharedLibrary.configured(app)) {
            runCatching { SharedLibrary.schedule(app, immediate = true) }
        }

        // Cada reintento procesa otro tramo; el tope evita bucles infinitos si
        // un proveedor o el almacén quedaran bloqueados indefinidamente.
        if (remaining && !stopped && runAttemptCount < 60) {
            // Reanudación automática del resto del lote.
            setProgress(workDataOf("done" to processed, "phase" to "reanudando…"))
            return@withContext Result.retry()
        }

        val review = rowsIn(db, books, BookClassificationEntity.STATUS_REVIEW)
        val pending = rowsIn(db, books, BookClassificationEntity.STATUS_PENDING)
        val summary = buildString {
            append(processed).append(" de ").append(total).append(" libros catalogados")
            append(skipped.takeIf { it > 0 }?.let { "; $it ya lo estaban" } ?: "")
            append('.')
            if (stopped) append(" Detenida: vuelve a lanzar la catalogación para reanudar.")
            if (review > 0) append(" ").append(review).append(" pendientes de revisión.")
            if (pending > 0) append(" ").append(pending)
                .append(" sin información suficiente (sin argumento ni materias).")
            if (aiDisabled && ask != null) append(" IA no disponible; comprueba las claves en Ajustes.")
            failures.take(5).forEach { append("\n").append(it.take(180)) }
        }
        Result.success(workDataOf("summary" to summary.take(1800), "done" to processed))
    }

    private suspend fun scope(db: AppDatabase, request: AutoCatalogQueue.Request): List<BookEntity> =
        if (request.all) db.backupDao().booksAll().sortedBy { it.title.lowercase() }
        else request.ids.mapNotNull { db.bookDao().findById(it) }

    private suspend fun rowsIn(db: AppDatabase, books: List<BookEntity>, status: String): Int {
        val ids = books.map { it.id }.toSet()
        return db.classificationDao().all().count { it.bookId in ids && it.status == status }
    }

    /** Adaptador IA con el mismo enrutado y consentimiento que la ficha manual. */
    private fun buildAsk(app: Context):
        (suspend (question: String, fragments: List<String>) -> AiClients.Answer)? {
        val geminiConfigured = AiKeyStore.getKey(app, AiClients.GEMINI) != null
        val groqConfigured = AiKeyStore.getKey(app, AiClients.GROQ) != null
        if (!geminiConfigured && !groqConfigured) return null
        return { question, fragments ->
            try {
                AiFallbackRouter.ask(
                    geminiConfigured = geminiConfigured,
                    groqConfigured = groqConfigured,
                    fallbackAllowed = { AiFallbackConsent.enabled(app) },
                ) { provider ->
                    val key = AiKeyStore.getKey(app, provider)
                        ?: throw AiClients.AiException(AiClients.AiErrorKind.INVALID_KEY,
                            "La clave del proveedor ya no está disponible.")
                    AiClients.ask(provider, key, question, fragments, emptyList(),
                        geminiModel = AiModelSettings.current(app),
                        systemInstruction = BookCatalog.SYSTEM_INSTRUCTION)
                }
            } catch (e: AiClients.AiException) {
                when (e.kind) {
                    AiClients.AiErrorKind.INVALID_KEY, AiClients.AiErrorKind.PERMISSION,
                    AiClients.AiErrorKind.MODEL, AiClients.AiErrorKind.PARSE,
                    AiClients.AiErrorKind.REQUEST -> throw AiConfigurationError(e)
                    else -> throw e
                }
            }
        }
    }
}
