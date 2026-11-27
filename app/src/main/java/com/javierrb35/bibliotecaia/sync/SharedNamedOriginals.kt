package com.javierrb35.bibliotecaia.sync

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import java.io.File
import java.io.IOException
import java.util.Locale

/** Native readable originals for the shared-folder mode only.
 * Never pass this store a legacy ZIP container. A verified receipt still
 * needs to be published by the shared catalogue. No remote delete/overwrite.
 * SAF listing/create is not an atomic distributed reservation: concurrent
 * first uploads must be tested on the actual provider before activation.
 */
internal object SharedNamedOriginals {
    private val formats = setOf("epub", "pdf", "mobi")
    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    internal fun validName(name: String): Boolean =
        name.isNotBlank() && name.length <= 240 &&
            name.none { it == '/' || it == '\\' || it.isISOControl() } &&
            !name.startsWith('.') && name.substringBeforeLast('.').isNotBlank() &&
            extension(name) in formats

    private fun mime(format: BookFormat) = when (format) {
        BookFormat.EPUB -> "application/epub+zip"
        BookFormat.PDF -> "application/pdf"
        BookFormat.MOBI -> "application/x-mobipocket-ebook"
    }

    /** Search existing native files by actual content, not title or filename.
     * Fail closed when a potential match is inaccessible. Receipts resolve duplicate names by verified hash and size.
     */
    fun find(store: SharedStore, book: BookEntity): SharedDocumentStore.Item? = find(store, book, store.list())

    internal fun find(store: SharedStore, book: BookEntity, listing: List<SharedDocumentStore.Item>,
                      excludedNames: Set<String> = emptySet()): SharedDocumentStore.Item? {
        val counts = listing.groupingBy { it.name }.eachCount()
        val files = listing.filter {
            !(it.name in excludedNames && counts[it.name] == 1) && validName(it.name) && extension(it.name) == book.format.name.lowercase(Locale.ROOT) &&
                (it.size == -1L || it.size == book.sizeBytes)
        }
        val match = files.sortedBy { it.name }.firstOrNull {
            try { store.verify(it, book.contentHash, book.sizeBytes) }
            catch (_: DocumentTooLargeException) { false }
        } ?: return null
        return match
    }

    fun ensure(store: SharedStore, book: BookEntity, source: File,
               excludedNames: Set<String> = emptySet()): SharedDocumentStore.Item {
        val ext = book.format.name.lowercase(Locale.ROOT)
        val original = if (validName(book.fileName) && extension(book.fileName) == ext) book.fileName else {
            val stem = book.fileName.substringBeforeLast('.', book.fileName)
                .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("")
                .trim().trimStart('.').take(220).ifBlank { "Libro" }
            "$stem.$ext"
        }
        require(source.isFile && source.length() == book.sizeBytes &&
            source.inputStream().use { SharedDocumentStore.digest(it, book.sizeBytes) } ==
                (book.contentHash to book.sizeBytes)) { "Original local no íntegro." }
        val listing = store.list()
        find(store, book, listing, excludedNames)?.let { return it }

        // Preserve original name exactly except where an existing distinct
        // book already occupies it. Never overwrite that file.
        val used = listing.map { it.name.lowercase(Locale.ROOT) }.toHashSet()
        var name = original
        var index = 2
        while (name.lowercase(Locale.ROOT) in used) {
            name = original.substringBeforeLast('.').take(220) + " (" + index++ + ")." + original.substringAfterLast('.')
            if (index > 10_000 || !validName(name)) throw IOException("No se puede conservar un nombre de archivo único.")
        }
        return source.inputStream().use {
            store.create(name, mime(book.format), it, book.contentHash, book.sizeBytes)
        }
    }
}
