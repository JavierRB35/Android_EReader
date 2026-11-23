package com.javierrb35.bibliotecaia.importer

import androidx.room.withTransaction
import com.javierrb35.bibliotecaia.data.AppDatabase
import java.io.File

/** Permanent removal from this library, distinct from evicting an offline copy. */
object LibraryBookRemover {
    suspend fun delete(db: AppDatabase, filesDir: File, bookId: String) {
        db.withTransaction {
            val book = db.bookDao().findById(bookId) ?: return@withTransaction
            if (book.localPath.isNotBlank() && File(book.localPath).isFile) {
                // Reuse the strict path validation; never delete a Drive/external original.
                LocalBookCopyRemover.delete(filesDir, book)
            }
            // These tables have no cascading foreign keys. Remove only this book's
            // dependent data, including versions of notes already in the trash.
            val sql = db.openHelper.writableDatabase
            val args = arrayOf<Any>(bookId)
            sql.execSQL("DELETE FROM sync_queue WHERE entityType = 'ANNOTATIONS' AND entityId IN (SELECT id FROM annotations WHERE bookId = ?)", args)
            sql.execSQL("DELETE FROM annotation_versions WHERE annotationId IN (SELECT id FROM annotations WHERE bookId = ?)", args)
            sql.execSQL("DELETE FROM annotations WHERE bookId = ?", args)
            sql.execSQL("DELETE FROM reading_positions WHERE bookId = ?", args)
            sql.execSQL("DELETE FROM book_synopses WHERE bookId = ?", args)
            sql.execSQL("DELETE FROM book_categories WHERE bookId = ?", args)
            sql.execSQL("DELETE FROM book_tags WHERE bookId = ?", args)
            sql.execSQL("DELETE FROM collection_books WHERE bookId = ?", args)
            sql.execSQL("DELETE FROM sync_queue WHERE entityType = 'BOOKS' AND entityId = ?", args)
            sql.execSQL("DELETE FROM books WHERE id = ?", args)
        }
    }
}
