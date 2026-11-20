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

    @Update
    suspend fun update(book: BookEntity)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int
}

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Query("SELECT * FROM categories ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("SELECT * FROM tags ORDER BY name")
    fun observeAll(): Flow<List<TagEntity>>
}

@Dao
interface CollectionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(collection: CollectionEntity)

    @Query("SELECT * FROM collections ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CollectionEntity>>
}
