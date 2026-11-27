package com.javierrb35.bibliotecaia.backup

import androidx.room.withTransaction
import com.javierrb35.bibliotecaia.data.AnnotationEntity
import com.javierrb35.bibliotecaia.data.AnnotationVersionEntity
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookDeletion
import java.io.File
import java.util.UUID

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
class BackupManager(private val db: AppDatabase, private val appVersion: String,
                    private val filesDir: File? = null) {

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
                synopses = db.synopsisDao().all(),
                deletedBooks = dao.deletedBooksAll(),
            )
        }
        return BackupCodec.toJson(snapshot, appVersion, exportedAt)
    }

    suspend fun restoreJson(json: String, availableOriginals: Map<String, String> = emptyMap(),
                            synchronize: Boolean = false): RestoreReport {
        // Validación ANTES de tocar la base (Issue #10.4): estructura, versión y entradas.
        val incomingSnapshot = BackupCodec.fromJson(json)
        val removed = mutableListOf<BookEntity>()
        val report = db.withTransaction {
            val dao = db.backupDao()
            incomingSnapshot.deletedBooks.forEach { BookDeletion.apply(db, it)?.let(removed::add) }
            val deletions = dao.deletedBooksAll().associate { it.contentHash to it.deletedAt }
            val snapshot = withoutDeletedBooks(incomingSnapshot, deletions)
            fun countInserted(ids: List<Long>) = ids.count { it != -1L }

            val identity = RestoreIdentityPlanner.plan(
                snapshot, dao.booksAll(), dao.categoriesAll(), dao.tagsAll(),
            )
            val booksRestored = countInserted(dao.insertBooksIgnore(snapshot.books))
            // La existencia previa del mismo original (mismo SHA-256) no impide
            // completar o corregir sus metadatos (Issue #155/#142): se reparan
            // solo valores genéricos, nunca ediciones reales del usuario.
            val booksByHash = dao.booksAll().associateBy { it.contentHash }
            var booksRepaired = 0
            for (incoming in snapshot.books) {
                val existing = booksByHash[incoming.contentHash] ?: continue
                val plan = MetadataReconciler.plan(existing, incoming)
                if (plan.hasChanges) {
                    db.bookDao().update(MetadataReconciler.apply(existing, plan))
                    booksRepaired++
                }
            }

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
            require(snapshot.synopses.all { identity.book(it.bookId) in knownBooks }) {
                "La copia contiene argumentos de libros desconocidos."
            }
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

            // Add only missing synopses; never overwrite a local synopsis when restoring.
            val synopses = snapshot.synopses.map { it.copy(bookId = identity.book(it.bookId)) }
            db.synopsisDao().insertIgnore(synopses)
            if (synchronize) for (synopsis in synopses) {
                val existing = db.synopsisDao().byBook(synopsis.bookId)
                // Un argumento importado del catálogo Calibre solo completa huecos;
                // jamás reemplaza el texto existente del usuario (Issue #155).
                if (existing != null && synopsis.provider == MetadataReconciler.IMPORTED_SYNOPSIS_PROVIDER) continue
                if (existing == null || synopsis.generatedAt > existing.generatedAt)
                    db.synopsisDao().save(synopsis)
            }

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
            val snapshotVersionsRestored = countInserted(dao.insertVersionsIgnore(snapshot.versions))
            var conflictVersionsRestored = 0
            if (synchronize) {
                val knownVersions = dao.versionsAll()
                    .mapTo(HashSet()) { VersionKey(it.annotationId, it.body, it.color) }
                val conflictVersions = mutableListOf<AnnotationVersionEntity>()
                for (note in remappedNotes) {
                    val existing = existingNotes[note.id] ?: continue
                    val resolution = resolveAnnotationConflict(existing, note)
                    if (resolution.applyIncoming) db.readingDao().updateAnnotation(note)
                    resolution.preservedVersion?.let { version ->
                        if (knownVersions.add(VersionKey(version.annotationId, version.body, version.color))) {
                            conflictVersions.add(version)
                        }
                    }
                }
                if (conflictVersions.isNotEmpty()) {
                    conflictVersionsRestored = countInserted(dao.insertVersionsIgnore(conflictVersions))
                }
            }
            val versionsRestored = snapshotVersionsRestored + conflictVersionsRestored

            var positionsUpdated = 0
            val positionsByBook = snapshot.positions.groupBy { identity.book(it.bookId) }
                .mapValues { (_, versions) -> versions.maxByOrNull { it.updatedAt }!! }
            for ((bookId, incoming) in positionsByBook) {
                require(incoming.progress.isFinite() && incoming.progress in 0f..1f) {
                    "La copia contiene progreso de lectura no válido."
                }
                val existing = dao.positionFor(bookId)
                if (existing == null || incoming.updatedAt > existing.updatedAt ||
                    (synchronize && incoming.updatedAt == existing.updatedAt &&
                        (incoming.locator > existing.locator ||
                            (incoming.locator == existing.locator && incoming.progress > existing.progress)))) {
                    dao.insertPositionReplace(incoming.copy(bookId = bookId))
                    dao.updateBookProgressAfterRestore(bookId, incoming.progress)
                    positionsUpdated++
                }
            }

            RestoreReport(
                booksRestored = booksRestored,
                booksRepaired = booksRepaired,
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
                versionsSkipped = snapshot.versions.size - snapshotVersionsRestored,
                positionsUpdated = positionsUpdated,
                positionsSkipped = snapshot.positions.size - positionsUpdated,
            )
        }
        // Only after successful commit, and only our own verified private path.
        filesDir?.let { directory ->
            for (book in removed) {
                if (book.localPath.isNotBlank() && File(book.localPath).isFile &&
                    db.bookDao().findById(book.id) == null) {
                    runCatching { com.javierrb35.bibliotecaia.importer.LocalBookCopyRemover.delete(directory, book) }
                }
            }
        }
        return report
    }

    /** Remove stale book-specific data before validation and identity mapping. */
    internal fun withoutDeletedBooks(snapshot: LibrarySnapshot, deletions: Map<String, Long>): LibrarySnapshot {
        val excluded = snapshot.books.filter { it.addedAt <= (deletions[it.contentHash] ?: Long.MIN_VALUE) }
            .map { it.id }.toSet()
        if (excluded.isEmpty()) return snapshot
        val notes = snapshot.annotations.filter { it.bookId in excluded }.map { it.id }.toSet()
        return snapshot.copy(
            books = snapshot.books.filterNot { it.id in excluded },
            bookCategories = snapshot.bookCategories.filterNot { it.bookId in excluded },
            bookTags = snapshot.bookTags.filterNot { it.bookId in excluded },
            collectionBooks = snapshot.collectionBooks.filterNot { it.bookId in excluded },
            annotations = snapshot.annotations.filterNot { it.id in notes },
            versions = snapshot.versions.filterNot { it.annotationId in notes },
            positions = snapshot.positions.filterNot { it.bookId in excluded },
            synopses = snapshot.synopses.filterNot { it.bookId in excluded })
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

    internal data class VersionKey(val annotationId: String, val body: String, val color: Int)
    internal data class AnnotationConflictResolution(
        val applyIncoming: Boolean,
        val preservedVersion: AnnotationVersionEntity?,
    )

    companion object {
        /**
         * Resolves concurrent annotation edits deterministically across devices
         * and preserves the losing text/color as an [AnnotationVersionEntity]
         * so offline edits are never silently lost.
         */
        internal fun resolveAnnotationConflict(
            existing: AnnotationEntity,
            incoming: AnnotationEntity,
        ): AnnotationConflictResolution {
            val applyIncoming = when {
                incoming.updatedAt != existing.updatedAt -> incoming.updatedAt > existing.updatedAt
                (incoming.deletedAt != null) != (existing.deletedAt != null) -> incoming.deletedAt != null
                (incoming.deletedAt ?: -1L) != (existing.deletedAt ?: -1L) ->
                    (incoming.deletedAt ?: -1L) > (existing.deletedAt ?: -1L)
                incoming.body != existing.body -> incoming.body > existing.body
                else -> incoming.color > existing.color
            }
            val preserved = if (existing.body != incoming.body || existing.color != incoming.color) {
                val losing = if (applyIncoming) existing else incoming
                AnnotationVersionEntity(
                    id = conflictVersionId(losing),
                    annotationId = losing.id,
                    body = losing.body,
                    color = losing.color,
                    savedAt = losing.updatedAt,
                )
            } else null
            return AnnotationConflictResolution(applyIncoming, preserved)
        }

        private fun conflictVersionId(note: AnnotationEntity): String =
            UUID.nameUUIDFromBytes(
                "conflict-v1|${note.id}|${note.updatedAt}|${note.color}|${note.body}"
                    .toByteArray(Charsets.UTF_8)
            ).toString()
    }
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
    val booksRepaired: Int = 0,
)
