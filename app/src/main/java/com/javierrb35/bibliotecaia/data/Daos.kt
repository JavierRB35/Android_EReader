package com.javierrb35.bibliotecaia.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(book: BookEntity)

    @Query("SELECT * FROM books WHERE contentHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): BookEntity?

    /** Catálogo observable: recientes primero (última apertura, luego fecha de añadido). */
    @Query(
        """
        SELECT * FROM books
        ORDER BY CASE WHEN lastOpenedAt IS NULL THEN 1 ELSE 0 END,
                 lastOpenedAt DESC,
                 addedAt DESC
        """
    )
    fun observeAll(): Flow<List<BookEntity>>

    /**
     * Búsqueda del catálogo (§7.5 parcial): título, autor, categoría, etiqueta y colección.
     * Filtros: texto libre, estante por estado ('ALL' = todos) y colección concreta.
     */
    @Query(
        """
        SELECT DISTINCT b.* FROM books b
        LEFT JOIN book_categories bc ON bc.bookId = b.id
        LEFT JOIN categories c ON c.id = bc.categoryId
        LEFT JOIN book_tags bt ON bt.bookId = b.id
        LEFT JOIN tags t ON t.id = bt.tagId
        LEFT JOIN collection_books cb ON cb.bookId = b.id
        LEFT JOIN collections col ON col.id = cb.collectionId
        WHERE (:query = '' OR b.title LIKE '%' || :query || '%'
              OR b.authors LIKE '%' || :query || '%'
              OR c.name LIKE '%' || :query || '%'
              OR t.name LIKE '%' || :query || '%'
              OR col.name LIKE '%' || :query || '%')
          AND (:status = 'ALL' OR b.readingStatus = :status)
          AND (:favoriteOnly = 0 OR b.favorite = 1)
          AND (:collectionId = '' OR cb.collectionId = :collectionId)
        ORDER BY CASE WHEN b.lastOpenedAt IS NULL THEN 1 ELSE 0 END,
                 b.lastOpenedAt DESC,
                 b.addedAt DESC
        """
    )
    fun search(query: String, status: String, favoriteOnly: Int, collectionId: String): Flow<List<BookEntity>>

    @Update
    suspend fun update(book: BookEntity)

    /** Clear only the removed file reference, without overwriting book metadata. */
    @Query("UPDATE books SET localPath = '' WHERE id = :bookId AND localPath = :expectedPath")
    suspend fun clearLocalPath(bookId: String, expectedPath: String): Int

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Query("SELECT * FROM books WHERE lastOpenedAt IS NOT NULL ORDER BY lastOpenedAt DESC LIMIT 1")
    suspend fun lastRead(): BookEntity?
}

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity)

    @Update
    suspend fun update(category: CategoryEntity)

    /** Elimina la categoría sin tocar los libros (§7.3); las referencias se limpian aparte. */
    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM book_categories WHERE categoryId = :categoryId")
    suspend fun deleteRefsForCategory(categoryId: String)

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    suspend fun allOnce(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<TagEntity>)

    @Update
    suspend fun update(tag: TagEntity)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM book_tags WHERE tagId = :tagId")
    suspend fun deleteRefsForTag(tagId: String)

    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun allOnce(): List<TagEntity>
}

@Dao
interface CollectionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(collection: CollectionEntity)

    @Update
    suspend fun update(collection: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM collection_books WHERE collectionId = :collectionId")
    suspend fun deleteRefsForCollection(collectionId: String)

    @Query("SELECT * FROM collections ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections ORDER BY createdAt DESC")
    suspend fun allOnce(): List<CollectionEntity>
}

/** Operaciones sobre las relaciones N:M libro↔categoría/etiqueta/colección. */
@Dao
interface RefsDao {

    // Categorías
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookCategory(ref: BookCategoryCrossRef)

    @Query("DELETE FROM book_categories WHERE bookId = :bookId AND categoryId = :categoryId")
    suspend fun removeBookCategory(bookId: String, categoryId: String)

    @Query("SELECT categoryId FROM book_categories WHERE bookId = :bookId")
    suspend fun categoryIdsForBook(bookId: String): List<String>

    @Query(
        "SELECT c.name FROM categories c INNER JOIN book_categories bc ON c.id = bc.categoryId " +
            "WHERE bc.bookId = :bookId ORDER BY c.name"
    )
    suspend fun categoryNamesForBook(bookId: String): List<String>

    // Etiquetas
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addBookTag(ref: BookTagCrossRef)

    @Query("DELETE FROM book_tags WHERE bookId = :bookId AND tagId = :tagId")
    suspend fun removeBookTag(bookId: String, tagId: String)

    @Query("SELECT tagId FROM book_tags WHERE bookId = :bookId")
    suspend fun tagIdsForBook(bookId: String): List<String>

    @Query(
        "SELECT t.name FROM tags t INNER JOIN book_tags bt ON t.id = bt.tagId " +
            "WHERE bt.bookId = :bookId ORDER BY t.name"
    )
    suspend fun tagNamesForBook(bookId: String): List<String>

    // Colecciones
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addCollectionBook(ref: CollectionBookCrossRef)

    @Query("DELETE FROM collection_books WHERE bookId = :bookId AND collectionId = :collectionId")
    suspend fun removeCollectionBook(bookId: String, collectionId: String)

    @Query("SELECT collectionId FROM collection_books WHERE bookId = :bookId")
    suspend fun collectionIdsForBook(bookId: String): List<String>

    @Query(
        "SELECT col.name FROM collections col INNER JOIN collection_books cb ON col.id = cb.collectionId " +
            "WHERE cb.bookId = :bookId ORDER BY col.name"
    )
    suspend fun collectionNamesForBook(bookId: String): List<String>
}
