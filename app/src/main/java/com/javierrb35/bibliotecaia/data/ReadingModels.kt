package com.javierrb35.bibliotecaia.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "reading_positions")
data class ReadingPositionEntity(
    @PrimaryKey val bookId: String,
    val locator: String,
    val progress: Float,
    val updatedAt: Long
)

/** Locator belongs to the immutable imported edition (book contentHash). */
@Entity(tableName = "annotations", indices = [Index("bookId")])
data class AnnotationEntity(
    @PrimaryKey val id: String,
    val bookId: String?,
    val locator: String?,
    val quote: String,
    val body: String,
    val kind: String,
    val color: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val deletedAt: Long? = null
)

@Entity(tableName = "annotation_versions", indices = [Index("annotationId")])
data class AnnotationVersionEntity(
    @PrimaryKey val id: String,
    val annotationId: String,
    val body: String,
    val color: Int,
    val savedAt: Long
)
