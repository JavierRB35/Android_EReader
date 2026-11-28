package com.javierrb35.bibliotecaia.catalog

import androidx.room.withTransaction
import com.javierrb35.bibliotecaia.ai.AiClients
import com.javierrb35.bibliotecaia.ai.BookCatalog
import com.javierrb35.bibliotecaia.ai.BookTextSource
import com.javierrb35.bibliotecaia.backup.CentralCatalogue
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookCategoryCrossRef
import com.javierrb35.bibliotecaia.data.BookClassificationEntity
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookSynopsisEntity
import com.javierrb35.bibliotecaia.data.BookTagCrossRef
import com.javierrb35.bibliotecaia.data.CategoryEntity
import com.javierrb35.bibliotecaia.data.TagEntity
import com.javierrb35.bibliotecaia.importer.BookRichMetadataParser
import com.javierrb35.bibliotecaia.importer.RichMetadata
import java.io.File
import java.util.UUID

/**
 * Catalogación automática de una ficha (Issue #142). Recupera el argumento con
 * esta prioridad: argumento ya guardado → descripción de los metadatos del
 * original (OPF/EXTH) → generación IA solo si hay copia local que aportar
 * fragmentos. Clasifica con categorías y etiquetas SOLO cuando faltan: jamás
 * sobrescribe argumentos, categorías o etiquetas existentes del usuario.
 *
 * La fila [BookClassificationEntity] es el registro de idempotencia: las fichas
 * con estado CLASSIFIED o REVIEW no se reprocesan; PENDING se reintenta.
 */
internal class AutoCatalogEngine(private val db: AppDatabase) {

    /** Resultado del procesamiento de una ficha individual. */
    enum class Outcome { PROCESSED, SKIPPED, FAILED }

    /**
     * @param original archivo verificado del que leer metadatos (copia local o
     * extracción temporal que el llamador borra); puede ser null.
     * @param ask adaptador IA con el enrutado Gemini→Groq existente; null si no
     * hay clave configurada (solo se completan argumento y estado, sin IA).
     */
    suspend fun process(
        book: BookEntity,
        original: File?,
        ask: (suspend (question: String, fragments: List<String>) -> AiClients.Answer)?,
        explicit: Boolean = false,
    ): Outcome {
        val current = db.bookDao().findByHash(book.contentHash) ?: return Outcome.SKIPPED
        val classification = db.classificationDao().byBook(current.id)
        if (!explicit && classification != null &&
            classification.status != BookClassificationEntity.STATUS_PENDING
        ) return Outcome.SKIPPED

        val synopsis = db.synopsisDao().byBook(current.id)?.takeIf { it.text.isNotBlank() }
        val hasCategories = db.refsDao().categoryIdsForBook(current.id).isNotEmpty()
        val hasTags = db.refsDao().tagIdsForBook(current.id).isNotEmpty()

        // 1) Ficha ya completa: registrar procedencia sin tocar nada.
        if (!explicit && synopsis != null && hasCategories && hasTags) {
            record(current.id, BookClassificationEntity.STATUS_CLASSIFIED,
                BookClassificationEntity.BASIS_EXISTING, null)
            return Outcome.PROCESSED
        }

        // 2) Argumento ausente → descripción de los metadatos del propio archivo.
        var workingSynopsis = synopsis
        var subjects: List<String> = emptyList()
        var metadataSynopsis = false
        if (original != null && original.isFile && (!explicit || synopsis == null)) {
            val rich: RichMetadata? = BookRichMetadataParser.parse(book.format, original)
            if (rich != null) {
                subjects = rich.tags
                if (workingSynopsis == null && !rich.description.isNullOrBlank()) {
                    db.withTransaction {
                        // Solo completa huecos: nunca sustituye un argumento existente.
                        if (db.synopsisDao().byBook(current.id) == null) {
                            db.synopsisDao().save(BookSynopsisEntity(
                                bookId = current.id,
                                text = rich.description.trim().take(100_000),
                                provider = com.javierrb35.bibliotecaia.backup.MetadataReconciler
                                    .IMPORTED_SYNOPSIS_PROVIDER,
                                generatedAt = System.currentTimeMillis(),
                                spoilerSafe = false))
                        }
                    }
                    workingSynopsis = db.synopsisDao().byBook(current.id)
                    metadataSynopsis = workingSynopsis != null
                }
            }
        }

        val needsCategories = explicit || !hasCategories
        val needsTags = !hasTags
        val localCopy = File(current.localPath)
        val canGenerate = synopsis == null && workingSynopsis == null &&
            ask != null && CentralCatalogue.local(current) && localCopy.isFile &&
            current.format.name != "PDF"

        // 3) Nada que asignar y sin posibilidad de generar: registrar estado.
        if (!needsCategories && !needsTags && !canGenerate) {
            record(current.id,
                if (workingSynopsis != null) BookClassificationEntity.STATUS_CLASSIFIED
                else BookClassificationEntity.STATUS_PENDING,
                if (metadataSynopsis) BookClassificationEntity.BASIS_METADATA
                else if (workingSynopsis != null) BookClassificationEntity.BASIS_EXISTING
                else BookClassificationEntity.BASIS_NONE, null)
            return Outcome.PROCESSED
        }

        // 5) Clasificación IA. Generar argumento solo con copia local (evita
        //    descargar libros completos para catalogar).
        val vocabulary = db.categoryDao().allOnce().map { it.name }
        val generateSynopsis = canGenerate
        val question = BookCatalog.prompt(
            title = current.title,
            authors = current.authors,
            synopsis = null,
            subjects = subjects,
            categoryVocabulary = vocabulary,
            generateSynopsis = generateSynopsis,
            proposeTags = needsTags,
        )
        val evidence: List<String> = if (generateSynopsis) {
            val chapters = BookTextSource.chapters(localCopy, current.format.name)
            BookTextSource.overview(chapters, spoilerSafe = false, progress = current.progress)
                .map { fragment -> fragment.text }
        } else {
            // El argumento recuperado es la evidencia de la clasificación; se
            // entrega acotado, nunca el libro completo. Sin argumento, las
            // materias del propio archivo son evidencia suficiente y honesta.
            listOfNotNull(workingSynopsis?.text?.take(2_000))
                .ifEmpty {
                    subjects.take(12).joinToString(", ").takeIf { it.isNotBlank() }
                        ?.let { listOf("Materias declaradas en el archivo: $it") } ?: emptyList()
                }
        }
        if (generateSynopsis && evidence.isEmpty()) {
            // Sin texto recuperable no hay base verificable para un argumento IA.
            record(current.id, BookClassificationEntity.STATUS_PENDING,
                if (metadataSynopsis) BookClassificationEntity.BASIS_METADATA
                else BookClassificationEntity.BASIS_NONE, null)
            return Outcome.PROCESSED
        }
        if (!generateSynopsis && evidence.isEmpty()) {
            // Clasificar sin argumento ni materias no es honesto: dejar pendiente
            // (#142: identificar los casos sin información suficiente).
            record(current.id, BookClassificationEntity.STATUS_PENDING,
                if (metadataSynopsis) BookClassificationEntity.BASIS_METADATA
                else BookClassificationEntity.BASIS_NONE, null)
            return Outcome.PROCESSED
        }
        if (ask == null) {
            // Sin IA disponible: conservar lo recuperado y dejar la ficha
            // pendiente; se reintenta al configurar la clave.
            record(current.id, BookClassificationEntity.STATUS_PENDING,
                if (metadataSynopsis) BookClassificationEntity.BASIS_METADATA
                else BookClassificationEntity.BASIS_NONE, null)
            return Outcome.PROCESSED
        }
        val answer = ask(question, evidence)
        val parsed = BookCatalog.parse(answer.text, expectSynopsis = generateSynopsis)

        db.withTransaction {
            requireNotNull(db.bookDao().findById(current.id)) { "El libro ya no existe." }
            var explicitCategoryAssigned = false
            var assigned = db.refsDao().categoryIdsForBook(current.id).isNotEmpty() ||
                db.refsDao().tagIdsForBook(current.id).isNotEmpty()
            // El argumento IA solo se guarda si sigue sin existir (contrato #142).
            if (generateSynopsis && parsed.synopsis != null &&
                db.synopsisDao().byBook(current.id) == null) {
                db.synopsisDao().save(BookSynopsisEntity(
                    bookId = current.id,
                    text = parsed.synopsis.take(100_000),
                    provider = answer.provider,
                    generatedAt = System.currentTimeMillis(),
                    spoilerSafe = false))
            }
            if ((explicit && !parsed.review) || (!explicit && db.refsDao().categoryIdsForBook(current.id).isEmpty())) {
                val categoriesAssigned = assignCategories(current.id, parsed.categories, allowNew = !parsed.review)
                explicitCategoryAssigned = categoriesAssigned
                assigned = categoriesAssigned || assigned
            }
            if ((!explicit || !parsed.review) && db.refsDao().tagIdsForBook(current.id).isEmpty()) {
                assigned = assignTags(current.id, parsed.tags) || assigned
            }
            val status = when {
                parsed.review -> BookClassificationEntity.STATUS_REVIEW
                (if (explicit) explicitCategoryAssigned else assigned) -> BookClassificationEntity.STATUS_CLASSIFIED
                else -> BookClassificationEntity.STATUS_PENDING
            }
            db.classificationDao().save(BookClassificationEntity(
                bookId = current.id,
                status = status,
                basis = BookClassificationEntity.BASIS_AI,
                provider = answer.provider,
                processedAt = System.currentTimeMillis()))
        }
        return Outcome.PROCESSED
    }

    private suspend fun record(bookId: String, status: String, basis: String, provider: String?) {
        db.withTransaction {
            requireNotNull(db.bookDao().findById(bookId)) { "El libro ya no existe." }
            db.classificationDao().save(BookClassificationEntity(
                bookId = bookId, status = status, basis = basis,
                provider = provider, processedAt = System.currentTimeMillis()))
        }
    }

    /** Categorías por nombre, reutilizando las existentes; como máximo UNA nueva
     *  (y solo sin dudas) para no fragmentar el vocabulario controlado. */
    private suspend fun assignCategories(bookId: String, names: List<String>, allowNew: Boolean): Boolean {
        if (names.isEmpty()) return false
        val existing = db.categoryDao().allOnce().toMutableList()
        val assigned = mutableListOf<CategoryEntity>()
        var created = 0
        for (raw in names.distinctBy { it.lowercase() }.take(3)) {
            val name = raw.trim().replace(Regex("""\s+"""), " ")
            if (name.length !in 2..40 || name.any { Character.isISOControl(it) }) continue
            val match = existing.firstOrNull { it.name.equals(name, true) }
            if (match != null) {
                assigned.add(match)
            } else if (allowNew && created == 0) {
                val next = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
                val category = CategoryEntity(UUID.randomUUID().toString(), name, null, next)
                db.categoryDao().insert(category)
                existing.add(category)
                assigned.add(category)
                created++
            }
        }
        for (category in assigned) {
            db.refsDao().addBookCategory(BookCategoryCrossRef(bookId, category.id))
        }
        return assigned.isNotEmpty()
    }

    /** Etiquetas con la misma validación que la sinopsis manual (#142). */
    private suspend fun assignTags(bookId: String, names: List<String>): Boolean {
        if (names.isEmpty()) return false
        val existing = db.tagDao().allOnce().toMutableList()
        var assigned = false
        for (raw in names.distinctBy { it.lowercase() }.take(5)) {
            val name = raw.trim().replace(Regex("""\s+"""), " ")
            if (name.length !in 2..40 || name.any { Character.isISOControl(it) }) continue
            val tag = existing.firstOrNull { it.name.equals(name, true) } ?: run {
                val created = TagEntity(UUID.randomUUID().toString(), name)
                db.tagDao().insert(created)
                existing.add(created)
                created
            }
            db.refsDao().addBookTag(BookTagCrossRef(bookId, tag.id))
            assigned = true
        }
        return assigned
    }
}
