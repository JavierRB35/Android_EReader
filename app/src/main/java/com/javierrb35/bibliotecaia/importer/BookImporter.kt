package com.javierrb35.bibliotecaia.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.javierrb35.bibliotecaia.data.BookDao
import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.UUID

/**
 * Importación de libros en modo local (Prompt Maestro §8.6).
 * Flujo: copia a temporal con hash en streaming → detección de formato por contenido →
 * control de duplicados → extracción de metadatos → colocación definitiva → registro en Room.
 * Nunca registra un libro cuyo archivo no exista; el original del usuario no se toca.
 */
class BookImporter(
    private val context: Context,
    private val bookDao: BookDao
) {

    /** Copia el stream a [dest] calculando hash y tamaño en una única pasada. */
    private class CopyResult(val hash: String, val sizeBytes: Long)

    private fun copyComputingHash(source: InputStream, dest: File): CopyResult {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        source.use { input ->
            dest.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                var read = input.read(buffer)
                while (read >= 0) {
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                    size += read
                    read = input.read(buffer)
                }
            }
        }
        return CopyResult(digest.digest().joinToString("") { "%02x".format(it) }, size)
    }

    /**
     * Importa un libro desde un Uri de SAF. Devuelve la entidad registrada.
     * Lanza [ImportError] con mensajes aptos para el usuario.
     */
    suspend fun import(uri: Uri): BookEntity {
        val fileName = queryDisplayName(uri) ?: DEFAULT_FILE_NAME
        val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.tmp")

        val copy = try {
            context.contentResolver.openInputStream(uri)?.use { copyComputingHash(it, tmp) }
                ?: throw ImportError.Reading(fileName, IllegalStateException("Sin acceso de lectura al archivo"))
        } catch (e: ImportError) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw ImportError.Reading(fileName, e)
        }

        try {
            // 1. Formato por contenido (§8.6.1)
            val detected = BookFormatDetector.detectFull { FileInputStream(tmp) }
            val format = when (detected) {
                DetectedFormat.EPUB -> BookFormat.EPUB
                DetectedFormat.PDF -> BookFormat.PDF
                DetectedFormat.MOBI -> BookFormat.MOBI
                DetectedFormat.UNKNOWN -> throw ImportError.UnknownFormat(fileName)
            }

            // 2. Duplicados por hash (§8.6.4)
            bookDao.findByHash(copy.hash)?.let {
                throw ImportError.Duplicate(it.title)
            }

            // 3. Metadatos (§8.6.5)
            val metadata = when (format) {
                BookFormat.EPUB -> EpubMetadataParser.parse { FileInputStream(tmp) }
                BookFormat.MOBI -> readMobiHeader(tmp)?.let { MobiMetadataParser.parse(it) }
                BookFormat.PDF -> null // título desde nombre; extracción de texto en Incremento 3
            }
            val title = metadata?.title?.takeIf { it.isNotBlank() }
                ?: TitleSanitizer.fromFileName(fileName)

            // 4. Colocación definitiva: nunca se mueve antes de validar
            val bookId = UUID.randomUUID().toString()
            val targetDir = File(context.filesDir, "books/$bookId").apply { mkdirs() }
            val safeName = fileName.replace(Regex("[/\\\\]"), "_")
            val target = File(targetDir, safeName)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }

            // 5. Registro coherente con la existencia real del archivo
            val entity = BookEntity(
                id = bookId,
                title = title,
                authors = metadata?.authors,
                format = format,
                fileName = safeName,
                localPath = target.absolutePath,
                contentHash = copy.hash,
                sizeBytes = copy.sizeBytes,
                addedAt = System.currentTimeMillis()
            )
            bookDao.insert(entity)
            return entity
        } catch (e: ImportError) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw ImportError.Reading(fileName, e)
        }
    }

    private fun readMobiHeader(file: File): ByteArray? = try {
        FileInputStream(file).use { stream ->
            val header = ByteArray(MobiMetadataParser.PDB_HEADER_SIZE)
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            header.copyOf(read)
        }
    } catch (_: Exception) {
        null
    }

    private fun queryDisplayName(uri: Uri): String? =
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
        } catch (_: Exception) {
            null
        }

    private companion object {
        const val DEFAULT_FILE_NAME = "libro"
    }
}
