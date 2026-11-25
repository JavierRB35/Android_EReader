package com.javierrb35.bibliotecaia.data
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "deleted_books")
data class DeletedBookEntity(@PrimaryKey val contentHash: String, val deletedAt: Long)
