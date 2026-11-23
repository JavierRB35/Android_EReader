package com.javierrb35.bibliotecaia.backup

import com.javierrb35.bibliotecaia.data.BookEntity
import java.io.File

/** Room holds metadata for recovery; it is not evidence of an original. */
object CentralCatalogue {
    fun local(book: BookEntity): Boolean =
        book.localPath.isNotBlank() && File(book.localPath).isFile

    fun visible(books: List<BookEntity>, central: Boolean, verified: Set<String>?): List<BookEntity> =
        books.filter { if (central) it.contentHash in verified.orEmpty() else local(it) }

    fun missing(books: List<BookEntity>, verified: Set<String>?): List<BookEntity> =
        books.filter { !local(it) && (verified == null || it.contentHash !in verified) }
}
