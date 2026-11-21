package com.javierrb35.bibliotecaia.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Saved synopsis is book-scoped, offline-readable and included in the complete backup. */
@Entity(tableName = "book_synopses")
data class BookSynopsisEntity(
    @PrimaryKey val bookId: String,
    val text: String,
    val provider: String,
    val generatedAt: Long,
    val spoilerSafe: Boolean,
)
