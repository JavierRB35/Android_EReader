package com.javierrb35.bibliotecaia.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Consultas dedicadas a la copia de seguridad/restauración (§16). No altera los DAO de dominio. */
@Dao
interface BackupDao {

    // ---------- Exportación ----------

    @Query("SELECT * FROM books")
    suspend fun booksAll(): List<BookEntity>

    @Query("SELECT * FROM categories ORDER BY sortOrder")
    suspend fun categoriesAll(): List<CategoryEntity>

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun tagsAll(): List<TagEntity>

    @Query("SELECT * FROM collections ORDER BY createdAt")
    suspend fun collectionsAll(): List<CollectionEntity>

    @Query("SELECT bookId, categoryId FROM book_categories")
    suspend fun bookCategoryPairs(): List<BookCategoryCrossRef>

    @Query("SELECT bookId, tagId FROM book_tags")
    suspend fun bookTagPairs(): List<BookTagCrossRef>

    @Query("SELECT collectionId, bookId FROM collection_books")
    suspend fun collectionBookPairs(): List<CollectionBookCrossRef>

    @Query("SELECT * FROM annotations")
    suspend fun annotationsAll(): List<AnnotationEntity>

    @Query("SELECT * FROM reading_positions")
    suspend fun positionsAll(): List<ReadingPositionEntity>

    @Query("SELECT * FROM annotation_versions")
    suspend fun versionsAll(): List<AnnotationVersionEntity>

    @Query("SELECT * FROM deleted_books")
    suspend fun deletedBooksAll(): List<DeletedBookEntity>
    @Query("SELECT * FROM deleted_books WHERE contentHash = :hash")
    suspend fun deletedBook(hash: String): DeletedBookEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDeletedBook(deletion: DeletedBookEntity)
    @Query("DELETE FROM annotation_versions WHERE annotationId IN (SELECT id FROM annotations WHERE bookId = :id)")
    suspend fun deleteBookAnnotationVersions(id: String)
    @Query("DELETE FROM annotations WHERE bookId = :id")
    suspend fun deleteBookAnnotations(id: String)
    @Query("DELETE FROM reading_positions WHERE bookId = :id")
    suspend fun deleteBookPosition(id: String)
    @Query("DELETE FROM book_categories WHERE bookId = :id")
    suspend fun deleteBookCategories(id: String)
    @Query("DELETE FROM book_tags WHERE bookId = :id")
    suspend fun deleteBookTags(id: String)
    @Query("DELETE FROM collection_books WHERE bookId = :id")
    suspend fun deleteBookCollections(id: String)
    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBookRow(id: String)

    // ---------- Restauración (aditiva; jamás borra ni sobrescribe libros existentes) ----------

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBooksIgnore(books: List<BookEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategoriesIgnore(categories: List<CategoryEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTagsIgnore(tags: List<TagEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionsIgnore(collections: List<CollectionEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookCategoriesIgnore(refs: List<BookCategoryCrossRef>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookTagsIgnore(refs: List<BookTagCrossRef>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCollectionBooksIgnore(refs: List<CollectionBookCrossRef>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAnnotationsIgnore(annotations: List<AnnotationEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertVersionsIgnore(versions: List<AnnotationVersionEntity>): List<Long>

    @Query("UPDATE books SET progress = :progress WHERE id = :bookId")
    suspend fun updateBookProgressAfterRestore(bookId: String, progress: Float)

    @Query("SELECT * FROM reading_positions WHERE bookId = :bookId")
    suspend fun positionFor(bookId: String): ReadingPositionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPositionReplace(position: ReadingPositionEntity)
}
