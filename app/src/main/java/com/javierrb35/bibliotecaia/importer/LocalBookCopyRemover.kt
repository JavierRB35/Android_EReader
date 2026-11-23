package com.javierrb35.bibliotecaia.importer

import com.javierrb35.bibliotecaia.data.BookEntity
import java.io.File

/**
 * Deletes only an app-owned book original, never a SAF/Drive document or an
 * arbitrary localPath supplied through a restored/legacy database.
 *
 * The database row is deliberately left to the caller: it must clear only the
 * localPath after this operation succeeds, preserving the library and notes.
 */
object LocalBookCopyRemover {
    fun delete(filesDir: File, book: BookEntity) {
        require(book.localPath.isNotBlank()) { "No hay copia local del libro." }
        require(book.id.isNotBlank() && book.id != "." && book.id != ".." &&
            book.id.none { it == '/' || it == '\\' || it.code < 32 }) {
            "Identificador de libro inválido."
        }
        val root = filesDir.canonicalFile
        val booksDir = File(root, "books").canonicalFile
        require(booksDir.parentFile == root) { "Ubicación de libros no válida." }

        val bookDir = File(booksDir, book.id).canonicalFile
        require(bookDir.parentFile == booksDir) { "Ubicación de libro no válida." }

        // Restored books may have a recovered filename different from fileName.
        // Their direct child under books/<bookId> is still the only safe target.
        val original = File(book.localPath).canonicalFile
        require(original.parentFile == bookDir) { "El archivo no pertenece a esta copia interna." }
        require(original.isFile) { "El archivo ya no existe en el dispositivo." }
        check(original.delete()) { "No se pudo borrar la copia interna." }

        // Never delete other objects in the book folder or a remote original.
        bookDir.delete() // succeeds only when empty; otherwise leaves it intact
    }
}
