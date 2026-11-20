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
 * exportJson/restoreJson support legacy metadata-only backups. A complete portable
 * backup including every imported original is handled by CompleteBackupManager.
 */
class BackupManager(private val db: AppDatabase, private val appVersion: String) {

    suspend fun exportJson(exportedAt: Long = System.currentTimeMillis()): String {
        val dao = db.backupDao()
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

    suspend fun restoreJson(json: String, availableOriginals: Map<String, String> = emptyMap()): RestoreReport {
        // Validación ANTES de tocar la base (Issue #10.4): estructura, versión y entradas.
        val snapshot = BackupCodec.fromJson(json)
        return db.withTransaction {
            val dao = db.backupDao()
            fun countInserted(ids: List<Long>) = ids.count { it != -1L }

            val identity = RestoreIdentityPlanner.plan(
                snapshot, dao.booksAll(), dao.categoriesAll(), dao.tagsAll(),
            )
            val booksRestored = countInserted(dao.insertBooksIgnore(snapshot.books))

            val categoriesToInsert = snapshot.categories.map {
                it.copy(id = identity.category(it.id),
                    parentId = it.parentId?.let { parent -> identity.category(parent) })
            }.distinctBy { it.id }
            val categoriesRestored = countInserted(dao.insertCategoriesIgnore(categoriesToInsert))
            val tagsToInsert = snapshot.tags.map { it.copy(id = identity.tag(it.id)) }
                .distinctBy { it.id }
            val tagsRestored = countInserted(dao.insertTagsIgnore(tagsToInsert))
            val collectionsRestored = countInserted(dao.insertCollectionsIgnore(snapshot.collections))

            // A complete archive verified and materialized its originals BEFORE
            // this transaction. Link paths atomically together with all metadata.
            if (availableOriginals.isNotEmpty()) {
                for (book in dao.booksAll()) {
                    val verifiedPath = availableOriginals[book.contentHash] ?: continue
                    val verified = File(verifiedPath)
                    require(matchesOriginal(verified, book)) {
                        "El original restaurado ha desaparecido o está dañado antes de guardar la biblioteca."
                    }
                    // A file's mere existence is not proof that it is this edition.
                    // Corruption can preserve the filename and even the byte length.
                    // Keep existing valid paths; replace only missing/corrupted links.
                    val current = book.localPath.takeIf { it.isNotBlank() }?.let(::File)
                    if (current == null || !matchesOriginal(current, book)) {
                        db.bookDao().update(book.copy(
                            localPath = verified.absolutePath,
                            fileName = verified.name,
                        ))
                    }
                }
            }
            val knownBooks = dao.booksAll().map { it.id }.toSet()
            val knownCategories = dao.categoriesAll().map { it.id }.toSet()
            val knownTags = dao.tagsAll().map { it.id }.toSet()
            val knownCollections = dao.collectionsAll().map { it.id }.toSet()

            require(snapshot.bookCategories.all {
                identity.book(it.bookId) in knownBooks &&
                    identity.category(it.categoryId) in knownCategories
            }) { "La copia contiene relaciones libro-categoría sin origen." }
            require(snapshot.bookTags.all {
                identity.book(it.bookId) in knownBooks &&
                    identity.tag(it.tagId) in knownTags
            }) { "La copia contiene relaciones libro-etiqueta sin origen." }
            require(snapshot.collectionBooks.all {
                identity.book(it.bookId) in knownBooks && it.collectionId in knownCollections
            }) { "La copia contiene relaciones libro-colección sin origen." }
            require(snapshot.annotations.all { note ->
                note.bookId == null || identity.book(note.bookId) in knownBooks
            }) { "La copia contiene anotaciones de libros desconocidos." }
            require(snapshot.positions.all { identity.book(it.bookId) in knownBooks }) {
                "La copia contiene posiciones de libros desconocidos."
            }

            dao.insertBookCategoriesIgnore(snapshot.bookCategories.map {
                it.copy(bookId = identity.book(it.bookId),
                    categoryId = identity.category(it.categoryId))
            })
            dao.insertBookTagsIgnore(snapshot.bookTags.map {
                it.copy(bookId = identity.book(it.bookId), tagId = identity.tag(it.tagId))
            })
            dao.insertCollectionBooksIgnore(snapshot.collectionBooks.map {
                it.copy(bookId = identity.book(it.bookId))
            })

            val existingNotes = dao.annotationsAll().associateBy { it.id }
            val remappedNotes = snapshot.annotations.map { note ->
                val mappedId = note.bookId?.let { identity.book(it) }
                val prior = existingNotes[note.id]
                require(prior == null || prior.bookId == mappedId) {
                    "La copia contiene una anotación que pertenece a otro libro."
                }
                note.copy(bookId = mappedId)
            }
            val annotationsRestored = countInserted(dao.insertAnnotationsIgnore(remappedNotes))
            val annotationIds = dao.annotationsAll().map { it.id }.toSet()
            require(snapshot.versions.all { it.annotationId in annotationIds }) {
                "La copia contiene versiones de una anotación inexistente."
            }
            val versionsRestored = countInserted(dao.insertVersionsIgnore(snapshot.versions))

            var positionsUpdated = 0
            val positionsByBook = snapshot.positions.groupBy { identity.book(it.bookId) }
                .mapValues { (_, versions) -> versions.maxByOrNull { it.updatedAt }!! }
            for ((bookId, incoming) in positionsByBook) {
                require(incoming.progress.isFinite() && incoming.progress in 0f..1f) {
                    "La copia contiene progreso de lectura no válido."
                }
                val existing = dao.positionFor(bookId)
                if (existing == null || incoming.updatedAt > existing.updatedAt) {
                    dao.insertPositionReplace(incoming.copy(bookId = bookId))
                    dao.updateBookProgressAfterRestore(bookId, incoming.progress)
                    positionsUpdated++
                }
            }

            RestoreReport(
                booksRestored = booksRestored,
                booksSkipped = snapshot.books.size - booksRestored,
                categoriesRestored = categoriesRestored,
                categoriesSkipped = snapshot.categories.size - categoriesRestored,
                tagsRestored = tagsRestored,
                tagsSkipped = snapshot.tags.size - tagsRestored,
                collectionsRestored = collectionsRestored,
                collectionsSkipped = snapshot.collections.size - collectionsRestored,
                annotationsRestored = annotationsRestored,
                annotationsSkipped = snapshot.annotations.size - annotationsRestored,
                versionsRestored = versionsRestored,
                versionsSkipped = snapshot.versions.size - versionsRestored,
                positionsUpdated = positionsUpdated,
                positionsSkipped = snapshot.positions.size - positionsUpdated,
            )
        }
    }

    /**
     * Validate both length and SHA-256 before trusting a restored or existing
     * original. A stale localPath can still point to a file of identical size.
     */
    private fun matchesOriginal(file: File, book: BookEntity): Boolean {
        if (!file.isFile || file.length() != book.sizeBytes) return false
        return OriginalLinker(file.parentFile ?: File(".")).sha256Hex(file) == book.contentHash
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
