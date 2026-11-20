package com.javierrb35.bibliotecaia.backup

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.CategoryEntity
import com.javierrb35.bibliotecaia.data.TagEntity
import java.util.Locale

/**
 * Deterministic identity remapping for additive restore on another device.
 *
 * A book already imported on the destination has a different UUID but the same
 * SHA-256. We must map its positions/notes/relations to that existing book,
 * preserving its valid local file path rather than creating orphaned records.
 */
internal data class RestoreIdentityMap(
    val books: Map<String, String>,
    val categories: Map<String, String>,
    val tags: Map<String, String>,
) {
    fun book(id: String): String = books[id] ?: id
    fun category(id: String): String = categories[id] ?: id
    fun tag(id: String): String = tags[id] ?: id
}

internal object RestoreIdentityPlanner {
    private fun normalName(name: String) = name.trim().lowercase(Locale.ROOT)

    fun plan(snapshot: LibrarySnapshot, existingBooks: List<BookEntity>,
             existingCategories: List<CategoryEntity>, existingTags: List<TagEntity>): RestoreIdentityMap {
        require(snapshot.books.map { it.id }.toSet().size == snapshot.books.size) {
            "La copia contiene identificadores de libros repetidos."
        }
        require(snapshot.books.map { it.contentHash }.toSet().size == snapshot.books.size) {
            "La copia contiene libros con el mismo contenido duplicado."
        }
        require(snapshot.categories.map { it.id }.toSet().size == snapshot.categories.size) {
            "La copia contiene identificadores de categorías repetidos."
        }
        require(snapshot.tags.map { it.id }.toSet().size == snapshot.tags.size) {
            "La copia contiene identificadores de etiquetas repetidos."
        }

        val booksById = existingBooks.associateBy { it.id }
        val booksByHash = existingBooks.associateBy { it.contentHash }
        val bookMap = snapshot.books.associate { incoming ->
            val sameId = booksById[incoming.id]
            require(sameId == null || sameId.contentHash == incoming.contentHash) {
                "La copia contiene un identificador de libro que pertenece a otra edición."
            }
            incoming.id to (sameId?.id ?: booksByHash[incoming.contentHash]?.id ?: incoming.id)
        }

        val categoriesById = existingCategories.associateBy { it.id }
        // Match ONLY root categories by name, to avoid duplicate default shelves on new phones.
        // Do not collapse identically named categories in different subtrees.
        val rootCats = existingCategories.filter { it.parentId == null }
            .associateBy { normalName(it.name) }
        val categoryMap = snapshot.categories.associate { incoming ->
            val sameId = categoriesById[incoming.id]
            require(sameId == null || sameId.name != incoming.name || sameId.parentId == incoming.parentId) {
                "La copia contiene una categoría con ubicación incompatible."
            }
            require(sameId == null || normalName(sameId.name) == normalName(incoming.name)) {
                "La copia contiene un identificador de categoría incompatible."
            }
            incoming.id to (sameId?.id ?:
                if (incoming.parentId == null) rootCats[normalName(incoming.name)]?.id ?: incoming.id
                else incoming.id)
        }

        val tagsById = existingTags.associateBy { it.id }
        val tagsByName = existingTags.associateBy { normalName(it.name) }
        val tagMap = snapshot.tags.associate { incoming ->
            val sameId = tagsById[incoming.id]
            require(sameId == null || normalName(sameId.name) == normalName(incoming.name)) {
                "La copia contiene un identificador de etiqueta incompatible."
            }
            incoming.id to (sameId?.id ?: tagsByName[normalName(incoming.name)]?.id ?: incoming.id)
        }

        return RestoreIdentityMap(bookMap, categoryMap, tagMap)
    }
}
