package com.javierrb35.bibliotecaia.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SynopsisDao {
    @Query("SELECT * FROM book_synopses WHERE bookId = :bookId LIMIT 1")
    suspend fun byBook(bookId: String): BookSynopsisEntity?

    @Query("DELETE FROM book_synopses WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String): Int

    @Query("SELECT * FROM book_synopses")
    suspend fun all(): List<BookSynopsisEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(synopsis: BookSynopsisEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(synopses: List<BookSynopsisEntity>): List<Long>
}
