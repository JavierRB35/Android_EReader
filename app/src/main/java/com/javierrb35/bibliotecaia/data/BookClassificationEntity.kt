package com.javierrb35.bibliotecaia.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Estado de la catalogación automática de una ficha (Issue #142). El argumento,
 * las categorías y las etiquetas viven en sus tablas propias; aquí solo se
 * registra la procedencia y si la clasificación requiere revisión humana.
 * Se sincroniza con el resto de la biblioteca dentro del mismo snapshot.
 *
 * status: CLASSIFIED (completa), REVIEW (asignada pero dudosa, pendiente de
 * revisión), PENDING (sin información suficiente para clasificar).
 * basis: EXISTING (ya organizado por el usuario o por migración), METADATA
 * (argumento del OPF/EXTH del original), AI (clasificación IA), NONE.
 */
@Entity(tableName = "book_classifications")
data class BookClassificationEntity(
    @PrimaryKey val bookId: String,
    val status: String,
    val basis: String,
    val provider: String?,
    val processedAt: Long,
) {
    companion object {
        const val STATUS_CLASSIFIED = "classified"
        const val STATUS_REVIEW = "review"
        const val STATUS_PENDING = "pending"
        const val BASIS_EXISTING = "existing"
        const val BASIS_METADATA = "metadata"
        const val BASIS_AI = "ai"
        const val BASIS_NONE = "none"

        val STATUS_VALUES = listOf(STATUS_CLASSIFIED, STATUS_REVIEW, STATUS_PENDING)
        val BASIS_VALUES = listOf(BASIS_EXISTING, BASIS_METADATA, BASIS_AI, BASIS_NONE)

        fun validStatus(status: String) = status in STATUS_VALUES
        fun validBasis(basis: String) = basis in BASIS_VALUES
    }
}

@Dao
interface ClassificationDao {
    @Query("SELECT * FROM book_classifications WHERE bookId = :bookId LIMIT 1")
    suspend fun byBook(bookId: String): BookClassificationEntity?

    @Query("SELECT * FROM book_classifications")
    suspend fun all(): List<BookClassificationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: BookClassificationEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entities: List<BookClassificationEntity>)

    @Query("DELETE FROM book_classifications WHERE bookId = :bookId")
    suspend fun deleteByBook(bookId: String)
}
