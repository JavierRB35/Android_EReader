package com.javierrb35.bibliotecaia.backup

import com.javierrb35.bibliotecaia.data.AppDatabase
import java.io.File

/**
 * Copia de seguridad local (Prompt Maestro §16): exportación completa de metadatos y
 * restauración **aditiva** — jamás borra ni sobrescribe libros existentes; las posiciones
 * de lectura se actualizan solo si la copia es más reciente (§15.5, sin retrocesos).
 *
 * Límite honesto de la V1: la copia incluye metadatos y anotaciones; los archivos
 * originales NO se incluyen (exportación completa con archivos: mejora futura). Un libro
 * restaurado conserva su fila pero debe reimportarse si falta su archivo.
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
                positions = dao.positionsAll(),
            ),
            appVersion,
            exportedAt,
        )
    }

    suspend fun restoreJson(json: String): RestoreReport {
        val snapshot = BackupCodec.fromJson(json)
        val dao = db.backupDao()

        val booksBefore = dao.booksAll().size
        val annotationsBefore = dao.annotationsAll().size

        dao.insertBooksIgnore(snapshot.books)
        dao.insertCategoriesIgnore(snapshot.categories)
        dao.insertTagsIgnore(snapshot.tags)
        dao.insertCollectionsIgnore(snapshot.collections)
        dao.insertBookCategoriesIgnore(snapshot.bookCategories)
        dao.insertBookTagsIgnore(snapshot.bookTags)
        dao.insertCollectionBooksIgnore(snapshot.collectionBooks)
        dao.insertAnnotationsIgnore(snapshot.annotations)

        var positionsUpdated = 0
        snapshot.positions.forEach {
            dao.upsertPositionIfNewer(it.bookId, it.locator, it.progress, it.updatedAt)
            positionsUpdated++
        }

        val booksAfter = dao.booksAll().size
        val annotationsAfter = dao.annotationsAll().size
        return RestoreReport(
            booksRestored = (booksAfter - booksBefore).coerceAtLeast(0),
            booksSkipped = snapshot.books.size - (booksAfter - booksBefore).coerceAtLeast(0),
            annotationsRestored = (annotationsAfter - annotationsBefore).coerceAtLeast(0),
            annotationsSkipped = snapshot.annotations.size - (annotationsAfter - annotationsBefore).coerceAtLeast(0),
            categoriesRestored = snapshot.categories.size,
            tagsRestored = snapshot.tags.size,
            collectionsRestored = snapshot.collections.size,
            positionsUpdated = positionsUpdated,
        )
    }

    /** Reconstruye la ruta estándar de un libro restaurado para comprobar si su archivo sigue presente. */
    fun expectedFile(bookId: String, fileName: String, filesDir: File): File =
        File(File(filesDir, "books/$bookId"), fileName)
}
