package com.javierrb35.bibliotecaia.ai

import androidx.room.withTransaction
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookSynopsisEntity
import com.javierrb35.bibliotecaia.data.BookTagCrossRef
import com.javierrb35.bibliotecaia.data.TagEntity
import java.util.UUID

/** Persist a successful answer and, only for untagged books, assign validated tags. */
internal class BookSynopsisRepository(private val db: AppDatabase) {
    suspend fun save(
        bookId: String,
        synopsis: String,
        provider: String,
        spoilerSafe: Boolean,
        suggestedTags: List<String>
    ): List<String> {
        require(synopsis.isNotBlank() && synopsis.length <= 100_000)
        return db.withTransaction {
            requireNotNull(db.bookDao().findById(bookId)) { "El libro ya no existe." }
            val tags = mutableListOf<String>()
            // This check must happen INSIDE the transaction: editing tags while
            // the API is responding must never result in overwritten selections.
            if (db.refsDao().tagIdsForBook(bookId).isEmpty()) {
                val existing = db.tagDao().allOnce().toMutableList()
                for (raw in suggestedTags.distinctBy { it.lowercase() }.take(5)) {
                    val name = raw.trim().replace(Regex("""\s+"""), " ")
                    if (name.length !in 2..40 || name.any { Character.isISOControl(it) }) continue
                    val tag = existing.firstOrNull { it.name.equals(name, true) } ?: run {
                        val created = TagEntity(UUID.randomUUID().toString(), name)
                        db.tagDao().insert(created)
                        existing.add(created)
                        created
                    }
                    db.refsDao().addBookTag(BookTagCrossRef(bookId, tag.id))
                    tags.add(tag.name)
                }
            }
            db.synopsisDao().save(
                BookSynopsisEntity(bookId, synopsis, provider, System.currentTimeMillis(), spoilerSafe)
            )
            tags
        }
    }
}
