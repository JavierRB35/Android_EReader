package com.javierrb35.bibliotecaia.backup

import android.content.Context
import com.javierrb35.bibliotecaia.data.AppDatabase
import com.javierrb35.bibliotecaia.data.BookEntity
import java.io.File
import java.security.MessageDigest

/**
 * Recuperación guiada de originales (ADR-008): tras restaurar una copia en un
 * dispositivo nuevo, los libros existen como metadatos sin archivo. El usuario
 * selecciona los originales (Drive u otro origen) mediante SAF y la app los
 * revincula comparando SHA-256 con `contentHash` — nunca por nombre — sin
 * duplicar fichas ni alterar anotaciones ni posiciones.
 */
class OriginalLinker(private val filesDir: File) {

    data class LinkAttempt(val bookId: String, val fileName: String, val linked: Boolean,
                           val hashMatched: Boolean)

    /** SHA-256 en streaming de un archivo (misma implementación que la importación). */
    fun sha256Hex(file: File): String? = try {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            var read = input.read(buffer)
            while (read >= 0) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Intenta vincular [picked] con el libro cuyo hash coincida.
     * Devuelve el libro vinculado o null si ningún libro espera ese hash.
     * El original NO se copia al backup ni se modifica: se guarda una copia local
     * para lectura offline (ADR-008) en la ruta estándar de la app.
     */
    fun link(picked: File, waiting: List<BookEntity>): BookEntity? {
        val hash = sha256Hex(picked) ?: return null
        val target = waiting.firstOrNull { it.contentHash == hash } ?: return null
        val targetDir = File(filesDir, "books/${target.id}").apply { mkdirs() }
        val destination = File(targetDir, target.fileName)
        if (!destination.exists()) picked.copyTo(destination, overwrite = false)
        return target.copy(localPath = destination.absolutePath)
    }

    /** Libros sin archivo disponible (tras restauración o borrado del almacenamiento). */
    fun missingFiles(books: List<BookEntity>): List<BookEntity> =
        books.filter { it.localPath.isBlank() || !File(it.localPath).exists() }
}
