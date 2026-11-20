package com.javierrb35.bibliotecaia.sync

import android.content.SharedPreferences
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.ReadingStatus
import com.javierrb35.bibliotecaia.data.SyncQueueEntry
import com.javierrb35.bibliotecaia.data.SyncRows
import com.javierrb35.bibliotecaia.sync.SupabaseRestClient.Result
import org.json.JSONArray
import org.json.JSONObject

/**
 * Motor de sincronización offline-first (§15.5): drena la cola local hacia Supabase
 * y extrae cambios remotos incrementales. Idempotente por UUID; sin sobrescritura
 * silenciosa de anotaciones; posiciones solo avanzan en el tiempo. Sin conexión,
 * la cola local conserva todo y el resultado es reintento limpio.
 */
class SyncEngine(
    private val db: AppDatabase,
    private val client: SupabaseRestClient,
    private val jwtProvider: suspend () -> String?,
    private val prefs: SharedPreferences,
) {



    private suspend fun jwt(): String = jwtProvider() ?: ""

    data class Outcome(
        val pushedBooks: Int = 0,
        val pushedAnnotations: Int = 0,
        val pulledBooks: Int = 0,
        val pulledAnnotations: Int = 0,
        val pendingLeft: Int = 0,
        val offline: Boolean = false,
        val errors: List<String> = emptyList(),
    )

    suspend fun syncNow(): Outcome {
        val jwt = jwtProvider() ?: return Outcome(errors = listOf("Sin sesión autenticada"))
        val errors = mutableListOf<String>()

        val pushed = pushQueue(jwt, errors)
        val pulled = pullRemote(jwt, errors)

        return Outcome(
            pushedBooks = pushed.first,
            pushedAnnotations = pushed.second,
            pulledBooks = pulled.first,
            pulledAnnotations = pulled.second,
            pendingLeft = db.syncQueueDao().all().size,
            offline = pulled.third,
            errors = errors,
        )
    }

    /** Encola un cambio local (llamar en cada escritura de dominio que deba sincronizarse). */
    suspend fun enqueue(entityType: String, entityId: String) {
        db.syncQueueDao().enqueue(
            SyncQueueEntry(entityType, entityId, "UPSERT", System.currentTimeMillis())
        )
    }

    // ---------- PUSH ----------

    private suspend fun pushQueue(jwt: String, errors: MutableList<String>): Pair<Int, Int> {
        val queue = db.syncQueueDao().all()
        if (queue.isEmpty()) return 0 to 0

        var pushedBooks = 0
        var pushedAnnotations = 0

        // Libros primero (las anotaciones remotas referencian book_id con FK de tenant)
        pushedBooks += pushEntity(
            SyncRows.BOOKS, SyncRows.REMOTE_BOOKS,
            queue.filter { it.entityType == SyncRows.BOOKS },
            errors,
        ) { id -> db.bookDao().findById(id)?.let { SyncRows.bookJson(it) to it.id } }

        pushedAnnotations += pushEntity(
            SyncRows.ANNOTATIONS, SyncRows.REMOTE_ANNOTATIONS,
            queue.filter { it.entityType == SyncRows.ANNOTATIONS },
            errors,
        ) { id -> db.readingDao().annotation(id)?.let { SyncRows.annotationJson(it) to it.id } }

        return pushedBooks to pushedAnnotations
    }

    /**
     * Empuja en un solo lote; si el lote falla, AISLA fila a fila.
     * Regla de seguridad (revisión del propietario): un fallo HTTP NUNCA descarta un
     * cambio pendiente — se registra en la entrada (intentos + error) y permanece en
     * la cola para reintento y diagnóstico. Solo el éxito borra.
     */
    private suspend fun pushEntity(
        entityType: String,
        remoteTable: String,
        entries: List<SyncQueueEntry>,
        errors: MutableList<String>,
        rowFor: suspend (String) -> Pair<JSONObject, String>?,
    ): Int {
        if (entries.isEmpty()) return 0
        val rows = JSONArray()
        val ids = mutableListOf<String>()
        entries.forEach { entry ->
            rowFor(entry.entityId)?.let { (row, realId) ->
                rows.put(row)
                ids.add(realId)
            }
        }
        if (rows.length() == 0) return 0

        when (val batch = client.pushUpsert(remoteTable, rows, jwt())) {
            is Result.Ok -> {
                db.syncQueueDao().clearMany(entityType, ids)
                return ids.size
            }
            is Result.Offline -> return 0 // sin red: cola intacta, reintento posterior
            is Result.Failure -> errors.add("$entityType lote: ${batch.message.take(200)}")
        }

        // Aislamiento fila a fila: una fila inválida no bloquea a las demás
        var pushed = 0
        for (index in 0 until rows.length()) {
            val single = JSONArray().put(rows.getJSONObject(index))
            val id = ids.getOrNull(index) ?: continue
            when (val one = client.pushUpsert(remoteTable, single, jwt())) {
                is Result.Ok -> { db.syncQueueDao().clearMany(entityType, listOf(id)); pushed++ }
                is Result.Offline -> return pushed
                is Result.Failure -> {
                    val kind = SyncPolicy.classifyFailure(one.status)
                    db.syncQueueDao().markFailed(entityType, id, "HTTP ${one.status} (${kind.label}): ${one.message.take(300)}")
                    if (kind == SyncPolicy.FailureKind.PERMANENT) {
                        errors.add("$entityType/${id.take(8)} rechazada (HTTP ${one.status}); la entrada se conserva para revisión")
                    }
                }
            }
        }
        return pushed
    }

    // ---------- PULL ----------

    private suspend fun pullRemote(
        jwt: String,
        errors: MutableList<String>,
    ): Triple<Int, Int, Boolean> {
        val booksSince = prefs.getLong(LAST_SYNC_BOOKS, 0)
        val annotationsSince = prefs.getLong(LAST_SYNC_ANNOTATIONS, 0)
        var pulledBooks = 0
        var pulledAnnotations = 0
        var offline = false

        when (val r = client.pullSince(SyncRows.REMOTE_BOOKS, booksSince, jwt)) {
            is Result.Ok -> {
                val rows = JSONArray(r.body)
                val localIds = db.backupDao().booksAll().map { it.id }.toSet()
                val now = System.currentTimeMillis()
                for (row in rows.mapList()) {
                    val entity = bookFrom(row) ?: continue
                    // Un libro remoto cuyo archivo no está localmente se registra
                    // con localPath vacío: la ficha indica reimportar (§16.4).
                    val exists = entity.id in localIds
                    if (!exists && row.optBoolean("deleted_present", false)) continue
                    db.backupDao().insertBooksIgnore(listOf(entity))
                    if (!exists) pulledBooks++
                }
                prefs.edit().putLong(LAST_SYNC_BOOKS, now).apply()
            }
            is Result.Offline -> offline = true
            is Result.Failure -> errors.add("Pull libros: ${r.message.take(200)}")
        }

        when (val r = client.pullSince(SyncRows.REMOTE_ANNOTATIONS, annotationsSince, jwt)) {
            is Result.Ok -> {
                val rows = JSONArray(r.body)
                val localIds = db.backupDao().annotationsAll().map { it.id }.toSet()
                for (row in rows.mapList()) {
                    val id = row.optString("id")
                    if (id.isEmpty()) continue
                    val entity = annotationFrom(row)
                    val existsLocally = id in localIds
                    val remoteDeleted = !row.isNull("deleted_at")
                    when {
                        SyncPolicy.applyRemoteAnnotation(existsLocally, remoteDeleted) -> {
                            db.readingDao().insertAnnotation(entity)
                            pulledAnnotations++
                        }
                        SyncPolicy.shouldTombstoneLocal(existsLocally, remoteDeleted) -> {
                            db.readingDao().annotation(id)?.let {
                                if (it.deletedAt == null) db.readingDao().updateAnnotation(it.copy(deletedAt = entity.deletedAt ?: System.currentTimeMillis()))
                            }
                        }
                        // existe y no borrada en remoto: se conserva la local (sin
                        // sobrescritura silenciosa; el historial de versiones protege el texto)
                    }
                }
                prefs.edit().putLong(LAST_SYNC_ANNOTATIONS, System.currentTimeMillis()).apply()
            }
            is Result.Offline -> offline = true
            is Result.Failure -> errors.add("Pull anotaciones: ${r.message.take(200)}")
        }
        return Triple(pulledBooks, pulledAnnotations, offline)
    }

    // ---------- filas remotas -> entidades locales ----------

    private fun JSONArray.mapList(): List<JSONObject> =
        List(length()) { i -> optJSONObject(i) ?: JSONObject() }

    private fun bookFrom(o: JSONObject): BookEntity? {
        val id = o.optString("id")
        if (id.isEmpty()) return null
        return BookEntity(
            id = id,
            title = o.optString("title", "Sin título"),
            authors = o.optString("authors").ifEmpty { null },
            format = runCatching { BookFormat.valueOf(o.optString("format", "EPUB")) }
                .getOrDefault(BookFormat.EPUB),
            fileName = o.optString("file_name", "libro"),
            localPath = "",
            contentHash = o.optString("content_hash"),
            sizeBytes = o.optLong("size_bytes", 0),
            addedAt = epoch(o.optString("added_at")),
            lastOpenedAt = epochOrNull(o.optString("last_opened_at")),
            readingStatus = runCatching { ReadingStatus.valueOf(o.optString("reading_status", "PENDING")) }
                .getOrDefault(ReadingStatus.PENDING),
            favorite = o.optBoolean("favorite", false),
            progress = o.optDouble("progress", 0.0).toFloat(),
        )
    }

    private fun annotationFrom(o: JSONObject): AnnotationEntity = AnnotationEntity(
        id = o.getString("id"),
        bookId = if (o.isNull("book_id")) null else o.optString("book_id"),
        locator = if (o.isNull("locator")) null else o.optJSONObject("locator")?.toString(),
        quote = o.optString("quote"),
        body = o.optString("body"),
        kind = o.optString("kind", "NOTE"),
        color = o.optInt("color", 0),
        createdAt = epoch(o.optString("created_at")),
        updatedAt = epoch(o.optString("updated_at")),
        deletedAt = epochOrNull(o.optString("deleted_at")),
    )

    private fun epoch(iso: String): Long =
        runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

    private fun epochOrNull(iso: String): Long? =
        runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull()

    private companion object {
        const val LAST_SYNC_BOOKS = "last_sync_books"
        const val LAST_SYNC_ANNOTATIONS = "last_sync_annotations"
    }
}
