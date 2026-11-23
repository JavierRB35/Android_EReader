package com.javierrb35.bibliotecaia.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Query("SELECT * FROM reading_positions WHERE bookId = :bookId")
    suspend fun position(bookId: String): ReadingPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePosition(position: ReadingPositionEntity)

    @Query("UPDATE books SET progress = :progress, lastOpenedAt = :now, readingStatus = CASE WHEN readingStatus = 'PENDING' THEN 'READING' ELSE readingStatus END WHERE id = :bookId")
    suspend fun updateBookProgress(bookId: String, progress: Float, now: Long)

    @Query("SELECT * FROM annotations WHERE bookId = :bookId AND deletedAt IS NULL ORDER BY createdAt")
    fun observeAnnotations(bookId: String): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE (:trash = 1 AND deletedAt IS NOT NULL OR :trash = 0 AND deletedAt IS NULL) AND (body LIKE '%' || :query || '%' OR quote LIKE '%' || :query || '%') ORDER BY updatedAt DESC")
    fun notebook(query: String, trash: Boolean): Flow<List<AnnotationEntity>>

    @Query("SELECT * FROM annotations WHERE id = :id")
    suspend fun annotation(id: String): AnnotationEntity?

    /** Exact persisted text anchor only: never remove a matching quote elsewhere in a book. */
    @Query("SELECT * FROM annotations WHERE bookId = :bookId AND locator = :locator " +
        "AND quote = :quote AND kind = 'HIGHLIGHT' AND body = '' AND deletedAt IS NULL")
    suspend fun activeHighlightsAt(bookId: String, locator: String, quote: String): List<AnnotationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAnnotation(note: AnnotationEntity)

    @Update
    suspend fun updateAnnotation(note: AnnotationEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertVersion(version: AnnotationVersionEntity)

    @Query("SELECT * FROM annotation_versions WHERE annotationId = :id ORDER BY savedAt DESC")
    suspend fun versions(id: String): List<AnnotationVersionEntity>

    @Query("SELECT * FROM annotations WHERE deletedAt IS NULL ORDER BY createdAt")
    suspend fun allNotes(): List<AnnotationEntity>
}
