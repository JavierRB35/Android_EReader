package com.javierrb35.bibliotecaia.data

import android.content.Context
import androidx.room.withTransaction
import java.util.UUID

class ReadingRepository(context: Context) {
    private val db = AppDatabase.get(context.applicationContext)
    private val dao = db.readingDao()

    suspend fun position(bookId: String) = dao.position(bookId)
    fun observeAnnotations(bookId: String) = dao.observeAnnotations(bookId)

    suspend fun savePosition(bookId: String, locator: String, progress: Float) {
        require(locator.length <= 65536 && locator.isNotBlank())
        require(progress.isFinite())
        val bounded = progress.coerceIn(0f, 1f)
        db.withTransaction {
            requireNotNull(db.bookDao().findById(bookId))
            val previous = dao.position(bookId)
            if (previous?.locator == locator && previous.progress == bounded) return@withTransaction
            val now = System.currentTimeMillis()
            dao.savePosition(ReadingPositionEntity(bookId, locator, bounded, now))
            dao.updateBookProgress(bookId, bounded, now)
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
    }

    suspend fun setDeleted(id: String, deleted: Boolean) {
        db.withTransaction {
            val old = requireNotNull(dao.annotation(id))
            val now = maxOf(System.currentTimeMillis(), old.updatedAt + 1)
            dao.updateAnnotation(old.copy(deletedAt = if (deleted) now else null, updatedAt = now))
        }
    }

    private fun validate(locator: String?, quote: String, body: String, kind: String) {
        require(kind in setOf("NOTE", "HIGHLIGHT", "BOOKMARK"))
        require((locator?.length ?: 0) <= 65536 && quote.length <= 100000 && body.length <= 100000)
        require(quote.isNotBlank() || body.isNotBlank() || kind == "BOOKMARK")
    }
}
