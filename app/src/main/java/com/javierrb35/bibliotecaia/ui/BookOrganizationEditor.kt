package com.javierrb35.bibliotecaia.ui

import androidx.room.withTransaction
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookCategoryCrossRef
import com.javierrb35.bibliotecaia.data.BookTagCrossRef
import com.javierrb35.bibliotecaia.data.CategoryEntity
import com.javierrb35.bibliotecaia.data.CollectionBookCrossRef
import com.javierrb35.bibliotecaia.data.CollectionEntity
import com.javierrb35.bibliotecaia.data.TagEntity
import java.util.UUID

/**
 * Category / tag / collection assignment from one book's details.
 *
 * Creating an item and assigning it to the book is a single Room transaction.
 * No other book, existing name, or library item is silently modified.
 */
internal class BookOrganizationEditor(private val db: AppDatabase) {
    enum class Kind { CATEGORY, TAG, COLLECTION }
    data class Option(val id: String, val name: String)
    data class Snapshot(val options: List<Option>, val selected: Set<String>)

    suspend fun snapshot(bookId: String, kind: Kind): Snapshot {
        requireNotNull(db.bookDao().findById(bookId))
        return Snapshot(all(kind), assigned(bookId, kind))
    }

    suspend fun save(bookId: String, kind: Kind, checkedIds: Set<String>, nameToCreate: String) {
        val normalizedName = nameToCreate.trim().replace(Regex("\\s+"), " ")
        require(normalizedName.length <= 64 &&
            normalizedName.none { Character.isISOControl(it) }) {
            "El nombre debe tener 64 caracteres como máximo y no contener controles."
        }
        db.withTransaction {
            requireNotNull(db.bookDao().findById(bookId)) { "Libro inexistente" }
            val available = all(kind)
            require(checkedIds.all { id -> available.any { it.id == id } }) {
                "La organización ha cambiado. Abre el editor de nuevo."
            }
            val idForName = if (normalizedName.isEmpty()) null else {
                available.firstOrNull { it.name.equals(normalizedName, ignoreCase = true) }?.id
                    ?: create(kind, normalizedName)
            }
            val next = checkedIds + listOfNotNull(idForName)
            val previous = assigned(bookId, kind)
            (next - previous).forEach { assign(bookId, kind, it) }
            (previous - next).forEach { unassign(bookId, kind, it) }
        }
    }

    private suspend fun all(kind: Kind): List<Option> = when (kind) {
        Kind.CATEGORY -> db.categoryDao().allOnce().map { Option(it.id, it.name) }
        Kind.TAG -> db.tagDao().allOnce().map { Option(it.id, it.name) }
        Kind.COLLECTION -> db.collectionDao().allOnce().map { Option(it.id, it.name) }
    }

    private suspend fun assigned(bookId: String, kind: Kind): Set<String> {
        val refs = db.refsDao()
        return when (kind) {
            Kind.CATEGORY -> refs.categoryIdsForBook(bookId)
            Kind.TAG -> refs.tagIdsForBook(bookId)
            Kind.COLLECTION -> refs.collectionIdsForBook(bookId)
        }.toSet()
    }

    private suspend fun create(kind: Kind, name: String): String {
        val id = UUID.randomUUID().toString()
        when (kind) {
            Kind.CATEGORY -> db.categoryDao().insert(
                CategoryEntity(id, name, parentId = null, sortOrder = db.categoryDao().allOnce().size)
            )
            Kind.TAG -> db.tagDao().insert(TagEntity(id, name))
            Kind.COLLECTION -> db.collectionDao().insert(
                CollectionEntity(id, name, createdAt = System.currentTimeMillis())
            )
        }
        return id
    }

    private suspend fun assign(bookId: String, kind: Kind, id: String) {
        when (kind) {
            Kind.CATEGORY -> db.refsDao().addBookCategory(BookCategoryCrossRef(bookId, id))
            Kind.TAG -> db.refsDao().addBookTag(BookTagCrossRef(bookId, id))
            Kind.COLLECTION -> db.refsDao().addCollectionBook(CollectionBookCrossRef(id, bookId))
        }
    }

    private suspend fun unassign(bookId: String, kind: Kind, id: String) {
        when (kind) {
            Kind.CATEGORY -> db.refsDao().removeBookCategory(bookId, id)
            Kind.TAG -> db.refsDao().removeBookTag(bookId, id)
            Kind.COLLECTION -> db.refsDao().removeCollectionBook(bookId, id)
        }
    }
}
