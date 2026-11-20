package com.javierrb35.bibliotecaia.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Formatos admitidos en la V1 (Prompt Maestro §8.1). */
enum class BookFormat { EPUB, PDF, MOBI }

/** Estados de lectura de la biblioteca (Prompt Maestro §7.1). FAVORITE se gestiona desde biblioteca en una rebanada posterior. */
enum class ReadingStatus { PENDING, READING, FAVORITE, FINISHED, ABANDONED }

@Entity(tableName = "books", indices = [Index(value = ["contentHash"], unique = true)])
data class BookEntity(
    @PrimaryKey val id: String,            // UUID v4 generado en la importación
    val title: String,
    val authors: String?,                  // "Autor1, Autor2"; tabla de autores en incrementos posteriores
    val format: BookFormat,
    val fileName: String,                  // nombre original conservado
    val localPath: String,                 // ruta absoluta de la copia en almacenamiento interno
    val contentHash: String,               // SHA-256 en hexadecimal
    val sizeBytes: Long,
    val addedAt: Long,                     // epoch millis
    val lastOpenedAt: Long? = null,
    val readingStatus: ReadingStatus = ReadingStatus.PENDING,
    val progress: Float = 0f               // 0..1; refinado con localizadores estables en el Incremento 2
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,          // subcategorías
    val sortOrder: Int = 0
)

@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey val id: String,
    val name: String
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long
)

/** Relaciones N:M libro-categoría / libro-etiqueta / libro-colección (sin duplicar libros). */
@Entity(tableName = "book_categories", primaryKeys = ["bookId", "categoryId"])
data class BookCategoryCrossRef(
    val bookId: String,
    val categoryId: String
)

@Entity(tableName = "book_tags", primaryKeys = ["bookId", "tagId"])
data class BookTagCrossRef(
    val bookId: String,
    val tagId: String
)

@Entity(tableName = "collection_books", primaryKeys = ["collectionId", "bookId"])
data class CollectionBookCrossRef(
    val collectionId: String,
    val bookId: String
)
