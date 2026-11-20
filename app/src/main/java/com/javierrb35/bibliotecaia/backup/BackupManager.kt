package com.javierrb35.bibliotecaia.backup

import androidx.room.withTransaction
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import java.io.File

/**
 * Copia de seguridad local (Prompt Maestro §16): exportación completa de metadatos y
 * restauración ATÓMICA y aditiva (Issue #10): todo el vuelco ocurre dentro de una
 * transacción Room; jamás borra ni sobrescribe libros/anotaciones existentes (IGNORE
 * por id/hash); posiciones solo avanzan si la copia es más reciente (§15.5); recuentos
 * reales de inserciones y omisiones.
 *
 * Límite honesto de la V1: la copia incluye metadatos y anotaciones; los archivos
 * originales NO se incluyen (exportación con archivos: mejora futura). Un libro
 * restaurado conserva su fila pero debe revincularse/reimportarse si falta su archivo
 * (ver OriginalLinker).
 */
class BackupManager(private val db: AppDatabase, private val appVersion: String) {

    suspend fun exportJson(exportedAt: Long = System.currentTimeMillis()): String {
        val dao = db.backupDao()
        return BackupCodec.toJson(
            LibrarySnapshot(
                books = dao.booksAll(),
                categories = dao.categoriesAll(),
                tags = dao.tagsAll(),
                collections = dao.collectionsAll(),
                bookCategories = dao.bookCategoryPairs(),
                bookTags = dao.bookTagPairs(),
                collectionBooks = dao.collectionBookPairs(),
                annotations = dao.annotationsAll(),
                versions = dao.versionsAll(),
                positions = dao.positionsAll(),
            ),
            appVersion,
            exportedAt,
        )
    }

    suspend fun restoreJson(json: String): RestoreReport {
        // Validación ANTES de tocar la base (Issue #10.4): estructura, versión y entradas.
        val snapshot = BackupCodec.fromJson(json)
        return db.withTransaction {
            val dao = db.backupDao()

            fun counts(inserted: List<Long>, size: Int): Pair<Int, Int> {
                val ok = inserted.count { it != -1L }
                return Pair(ok, size - ok)
            }

            val existingHashes = dao.booksAll().map { it.contentHash }.toSet()
            val booksResult = dao.insertBooksIgnore(snapshot.books)
            val (booksRestored, booksIgnoredById) = counts(booksResult, snapshot.books.size)
            val duplicateHash = snapshot.books.map { it.contentHash }.count { it in existingHashes }

            val (categoriesRestored, categoriesIgnored) =
                counts(dao.insertCategoriesIgnore(snapshot.categories), snapshot.categories.size)
            val (tagsRestored, tagsIgnored) =
                counts(dao.insertTagsIgnore(snapshot.tags), snapshot.tags.size)
            val (collectionsRestored, collectionsIgnored) =
                counts(dao.insertCollectionsIgnore(snapshot.collections), snapshot.collections.size)
            dao.insertBookCategoriesIgnore(snapshot.bookCategories)
            dao.insertBookTagsIgnore(snapshot.bookTags)
            dao.insertCollectionBooksIgnore(snapshot.collectionBooks)

            val (annotationsRestored, annotationsIgnored) =
                counts(dao.insertAnnotationsIgnore(snapshot.annotations), snapshot.annotations.size)

            val annotationIds = dao.annotationsAll().map { it.id }.toSet()
            val validVersions = snapshot.versions.filter { it.annotationId in annotationIds }
            val (versionsRestored, versionsIgnored) =
                counts(dao.insertVersionsIgnore(validVersions), validVersions.size)

            val bookIds = dao.booksAll().map { it.id }.toSet()
            var positionsUpdated = 0
            var positionsSkipped = 0
            snapshot.positions.forEach { position ->
                if (position.bookId !in bookIds) {
                    positionsSkipped++
                    return@forEach
                }
                val current = dao.positionFor(position.bookId)
                if (current == null || position.updatedAt > current.updatedAt) {
                    dao.insertPositionReplace(position)
                    positionsUpdated++
                } else {
                    positionsSkipped++
                }
            }

            RestoreReport(
                booksRestored = booksRestored,
                booksSkipped = booksIgnoredById + duplicateHash,
                categoriesRestored = categoriesRestored, categoriesSkipped = categoriesIgnored,
                tagsRestored = tagsRestored, tagsSkipped = tagsIgnored,
                collectionsRestored = collectionsRestored, collectionsSkipped = collectionsIgnored,
                annotationsRestored = annotationsRestored, annotationsSkipped = annotationsIgnored,
                versionsRestored = versionsRestored, versionsSkipped = versionsIgnored,
                positionsUpdated = positionsUpdated, positionsSkipped = positionsSkipped,
            )
        }
    }

    /** Libros sin archivo disponible (tras restauración o borrado del almacenamiento). */
    fun missingFiles(books: List<BookEntity>): List<BookEntity> =
        books.filter { it.localPath.isBlank() || !File(it.localPath).exists() }
}

/** Resultado de una restauración con recuentos reales de inserciones y omisiones. */
data class RestoreReport(
    val booksRestored: Int,
    val booksSkipped: Int,
    val categoriesRestored: Int,
    val categoriesSkipped: Int,
    val tagsRestored: Int,
    val tagsSkipped: Int,
    val collectionsRestored: Int,
    val collectionsSkipped: Int,
    val annotationsRestored: Int,
    val annotationsSkipped: Int,
    val versionsRestored: Int,
    val versionsSkipped: Int,
    val positionsUpdated: Int,
    val positionsSkipped: Int,
)
