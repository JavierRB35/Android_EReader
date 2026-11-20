package com.javierrb35.bibliotecaia.backup

import androidx.room.withTransaction
import com.javierrb35.bibliotecaia.data.AppDatabase
import java.io.File

/**
 * Additive and atomic backup/restore. Originals remain in Google Drive.
 * Data restoration is an all-or-nothing Room transaction and never replaces a
 * book's local file, nor overwrites a local annotation edited since backup.
 */
class BackupManager(private val db: AppDatabase, private val appVersion: String,
                    private val filesDir: File? = null) {

    suspend fun exportJson(exportedAt: Long = System.currentTimeMillis()): String {
        val dao = db.backupDao()
        // All tables must come from one consistent database snapshot.
        val snapshot = db.withTransaction {
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
            )
        }
        return BackupCodec.toJson(snapshot, appVersion, exportedAt)
    }

    suspend fun restoreJson(json: String): RestoreReport {
        // Decode/validate BEFORE acquiring a write transaction.
        val snapshot = BackupCodec.fromJson(json)
        val dao = db.backupDao()

        return db.withTransaction {
            val existingBooks = dao.booksAll()
            val existingCategories = dao.categoriesAll()
            val existingTags = dao.tagsAll()
            val existingAnnotations = dao.annotationsAll()
            val plan = RestoreIdentityPlanner.plan(snapshot, existingBooks,
                existingCategories, existingTags)

            val addedBooks = dao.insertBooksIgnore(snapshot.books).count { it != -1L }
            val remappedCategories = snapshot.categories.map { category ->
                category.copy(
                    id = plan.category(category.id),
                    parentId = category.parentId?.let { plan.category(it) },
                )
            }.distinctBy { it.id }
            val addedCategories = dao.insertCategoriesIgnore(remappedCategories).count { it != -1L }
            val remappedTags = snapshot.tags.map { it.copy(id = plan.tag(it.id)) }
                .distinctBy { it.id }
            val addedTags = dao.insertTagsIgnore(remappedTags).count { it != -1L }
            val addedCollections = dao.insertCollectionsIgnore(snapshot.collections).count { it != -1L }

            val knownBooks = dao.booksAll().map { it.id }.toSet()
            val knownCategories = dao.categoriesAll().map { it.id }.toSet()
            val knownTags = dao.tagsAll().map { it.id }.toSet()
            val knownCollections = dao.collectionsAll().map { it.id }.toSet()

            // A corrupt backup must fail and roll back, never create orphan references.
            require(snapshot.bookCategories.all {
                plan.book(it.bookId) in knownBooks && plan.category(it.categoryId) in knownCategories
            }) { "La copia contiene relaciones libro-categoría sin origen." }
            require(snapshot.bookTags.all {
                plan.book(it.bookId) in knownBooks && plan.tag(it.tagId) in knownTags
            }) { "La copia contiene relaciones libro-etiqueta sin origen." }
            require(snapshot.collectionBooks.all {
                plan.book(it.bookId) in knownBooks && it.collectionId in knownCollections
            }) { "La copia contiene relaciones libro-colección sin origen." }
            require(snapshot.annotations.all { it.bookId == null || plan.book(it.bookId) in knownBooks }) {
                "La copia contiene anotaciones de libros desconocidos."
            }
            require(snapshot.positions.all { plan.book(it.bookId) in knownBooks }) {
                "La copia contiene posiciones de libros desconocidos."
            }

            dao.insertBookCategoriesIgnore(snapshot.bookCategories.map {
                it.copy(bookId = plan.book(it.bookId), categoryId = plan.category(it.categoryId))
            })
            dao.insertBookTagsIgnore(snapshot.bookTags.map {
                it.copy(bookId = plan.book(it.bookId), tagId = plan.tag(it.tagId))
            })
            dao.insertCollectionBooksIgnore(snapshot.collectionBooks.map {
                it.copy(bookId = plan.book(it.bookId))
            })

            val existingNotesById = existingAnnotations.associateBy { it.id }
            val remappedNotes = snapshot.annotations.map { annotation ->
                val mappedBook = annotation.bookId?.let { plan.book(it) }
                val old = existingNotesById[annotation.id]
                require(old == null || old.bookId == mappedBook) {
                    "La copia contiene una anotación vinculada a otro libro."
                }
                annotation.copy(bookId = mappedBook)
            }
            val addedNotes = dao.insertAnnotationsIgnore(remappedNotes).count { it != -1L }
            val noteIds = dao.annotationsAll().map { it.id }.toSet()
            require(snapshot.versions.all { it.annotationId in noteIds }) {
                "La copia contiene versiones de una anotación inexistente."
            }
            dao.insertVersionsIgnore(snapshot.versions)

            var positionsUpdated = 0
            // One effective, most-recent position per destination book.
            val incomingPositions = snapshot.positions.groupBy { plan.book(it.bookId) }
                .mapValues { (_, items) -> items.maxByOrNull { it.updatedAt }!! }
            for ((bookId, position) in incomingPositions) {
                val current = dao.positionUpdatedAt(bookId)
                if (current == null || position.updatedAt > current) {
                    dao.upsertPositionIfNewer(bookId, position.locator,
                        position.progress, position.updatedAt)
                    dao.updateBookProgressAfterRestore(bookId, position.progress)
                    positionsUpdated++
                }
            }

            RestoreReport(
                booksRestored = addedBooks,
                booksSkipped = snapshot.books.size - addedBooks,
                annotationsRestored = addedNotes,
                annotationsSkipped = snapshot.annotations.size - addedNotes,
                categoriesRestored = addedCategories,
                tagsRestored = addedTags,
                collectionsRestored = addedCollections,
                positionsUpdated = positionsUpdated,
            )
        }
    }

    /** Original-path recovery is separate; a JSON backup never contains book files. */
    fun expectedFile(bookId: String, fileName: String, filesDir: File): File =
        File(File(filesDir, "books/$bookId"), fileName)
}
