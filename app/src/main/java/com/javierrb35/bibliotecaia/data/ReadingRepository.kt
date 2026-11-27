package com.javierrb35.bibliotecaia.data

import android.content.Context
import androidx.room.withTransaction
import com.javierrb35.bibliotecaia.sync.ReadingPositionSafSync
import java.util.UUID

class ReadingRepository(
    private val db: AppDatabase,
    private val appContext: Context? = null
) {
    constructor(context: Context) : this(AppDatabase.get(context.applicationContext),
        context.applicationContext)
    private val dao = db.readingDao()

    suspend fun position(bookId: String) = dao.position(bookId)
    fun observeAnnotations(bookId: String) = dao.observeAnnotations(bookId)

    suspend fun markOpened(bookId: String) = dao.markOpened(bookId, System.currentTimeMillis())

    suspend fun savePosition(bookId: String, locator: String, progress: Float) {
        require(locator.length <= 65536 && locator.isNotBlank())
        require(progress.isFinite())
        val bounded = progress.coerceIn(0f, 1f)
        var changed = false
        db.withTransaction {
            requireNotNull(db.bookDao().findById(bookId))
            val previous = dao.position(bookId)
            if (previous?.locator == locator && previous.progress == bounded) return@withTransaction
            // A real movement after restoring a peer position must be newer.
            // Re-saving the same location never refreshes its timestamp.
            val now = maxOf(System.currentTimeMillis(), (previous?.updatedAt ?: 0L) + 1L)
            dao.savePosition(ReadingPositionEntity(bookId, locator, bounded, now))
            dao.updateBookProgress(bookId, bounded, now)
            changed = true
        }
        if (changed) appContext?.let {
            ReadingPositionSafSync.requestPublish(it)
            // Keep the old transport until all JSON files have been linked.
            if (com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(it) &&
                !com.javierrb35.bibliotecaia.sync.SharedLibrary.namedFolderMode(it) &&
                !ReadingPositionSafSync.fastReady(it))
                com.javierrb35.bibliotecaia.sync.SharedLibrary.schedule(it)
        }
    }

    private fun requestSync() {
        appContext?.let {
            if (com.javierrb35.bibliotecaia.sync.SharedLibrary.configured(it))
                com.javierrb35.bibliotecaia.sync.SharedLibrary.schedule(it)
            else ReadingPositionSafSync.requestPublish(it)
        }
    }

    suspend fun addAnnotation(bookId: String?, locator: String?, quote: String, body: String,
                              kind: String = "NOTE", color: Int = 0xFFD4B483.toInt()): String {
        validate(locator, quote, body, kind)
        val id = UUID.randomUUID().toString()
        db.withTransaction {
            if (bookId != null) requireNotNull(db.bookDao().findById(bookId))
            val now = System.currentTimeMillis()
            dao.insertAnnotation(AnnotationEntity(id, bookId, locator, quote, body.trim(), kind, color, now, now))
        }
        requestSync()
        return id
    }

    /** Version and edit commit together; expected timestamp prevents silent stale-editor overwrite. */
    suspend fun editAnnotation(id: String, expectedUpdatedAt: Long, body: String, color: Int) {
        require(body.length <= 100000)
        db.withTransaction {
            val old = requireNotNull(dao.annotation(id))
            check(old.updatedAt == expectedUpdatedAt && old.deletedAt == null) { "La nota ha cambiado. Vuelve a abrirla." }
            require(body.isNotBlank() || old.quote.isNotBlank() || old.kind == "BOOKMARK")
            val now = maxOf(System.currentTimeMillis(), old.updatedAt + 1)
            dao.insertVersion(AnnotationVersionEntity(UUID.randomUUID().toString(), id, old.body, old.color, now))
            dao.updateAnnotation(old.copy(body = body.trim(), color = color, updatedAt = now))
        }
        requestSync()
    }

    /**
     * Move a note to the trash together with any independently saved underline
     * on precisely the same selection. The NOTE itself also paints a mark, so
     * all reader surfaces update through their existing Room observers.
     *
     * Matching on book + complete locator + quote prevents deleting identical
     * wording elsewhere. Soft deletion preserves recovery and backup history.
     */
    suspend fun setDeleted(id: String, deleted: Boolean) {
        db.withTransaction {
            val old = requireNotNull(dao.annotation(id))
            if ((old.deletedAt != null) == deleted) return@withTransaction
            val now = maxOf(System.currentTimeMillis(), old.updatedAt + 1)
            dao.updateAnnotation(old.copy(deletedAt = if (deleted) now else null, updatedAt = now))

            if (deleted && old.kind == "NOTE" && old.bookId != null &&
                !old.locator.isNullOrBlank() && old.quote.isNotBlank()) {
                dao.activeHighlightsAt(old.bookId, old.locator, old.quote).forEach { underline ->
                    val removedAt = maxOf(now, underline.updatedAt + 1)
                    dao.updateAnnotation(underline.copy(deletedAt = removedAt, updatedAt = removedAt))
                }
            }
        }
        requestSync()
    }

    private fun validate(locator: String?, quote: String, body: String, kind: String) {
        require(kind in setOf("NOTE", "HIGHLIGHT", "BOOKMARK"))
        require((locator?.length ?: 0) <= 65536 && quote.length <= 100000 && body.length <= 100000)
        require(quote.isNotBlank() || body.isNotBlank() || kind == "BOOKMARK")
    }
}
