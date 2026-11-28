package com.javierrb35.bibliotecaia.data

import androidx.room.withTransaction

object BookDeletion {
    /** Must run in the enclosing transaction. A stale snapshot never undoes a deletion. */
    internal suspend fun apply(db: AppDatabase, deletion: DeletedBookEntity): BookEntity? {
        val dao = db.backupDao()
        val effective = maxOf(deletion.deletedAt, dao.deletedBook(deletion.contentHash)?.deletedAt ?: 0L)
        dao.saveDeletedBook(deletion.copy(deletedAt = effective))
        val book = db.bookDao().findByHash(deletion.contentHash) ?: return null
        if (book.addedAt > effective) return null
        dao.deleteBookAnnotationVersions(book.id)
        dao.deleteBookAnnotations(book.id)
        dao.deleteBookPosition(book.id)
        dao.deleteBookCategories(book.id)
        dao.deleteBookTags(book.id)
        dao.deleteBookCollections(book.id)
        db.synopsisDao().deleteByBook(book.id)
        db.classificationDao().deleteByBook(book.id)
        dao.deleteBookRow(book.id)
        return book
    }

    suspend fun remove(db: AppDatabase, id: String): BookEntity? = db.withTransaction {
        val book = db.bookDao().findById(id) ?: return@withTransaction null
        apply(db, DeletedBookEntity(book.contentHash, maxOf(System.currentTimeMillis(), book.addedAt)))
    }
}
