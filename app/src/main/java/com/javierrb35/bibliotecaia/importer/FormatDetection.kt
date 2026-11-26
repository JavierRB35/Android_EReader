package com.javierrb35.bibliotecaia.importer

import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/** Formato detectado por contenido (no por extensión). */
enum class DetectedFormat { EPUB, PDF, MOBI, UNKNOWN }

/** Errores recuperables de importación con mensaje orientado al usuario (protocolo §17). */
sealed class ImportError(message: String) : Exception(message) {
    class UnknownFormat(fileName: String) :
        ImportError("«$fileName» no es un EPUB o MOBI válido. El archivo no se ha importado.")

    class UnsupportedPdf(fileName: String) :
        ImportError("«$fileName» es PDF. BibliotecaAI solo importa libros EPUB y MOBI.")

    class Duplicate(existingTitle: String) :
        ImportError("Ya existe en la biblioteca un libro idéntico: «$existingTitle» (hash de contenido coincidente).")

    class Reading(fileName: String, cause: Throwable) :
        ImportError("No se pudo leer «$fileName»: ${cause.message ?: cause.javaClass.simpleName}")
}

/**
 * Detección real de formato a partir del contenido (Prompt Maestro §8.6.1).
 * - PDF: cabecera "%PDF-"
 * - MOBI: magic "BOOKMOBI" en el offset 60 de la cabecera PDB
 * - EPUB: archivo ZIP cuyo primer recurso "mimetype" es "application/epub+zip"
 * La extensión solo se usa como pista cuando el contenido no permite decidir.
 */
object BookFormatDetector {
    fun importFormat(detected: DetectedFormat, fileName: String): com.javierrb35.bibliotecaia.data.BookFormat = when (detected) {
        DetectedFormat.EPUB -> com.javierrb35.bibliotecaia.data.BookFormat.EPUB
        DetectedFormat.MOBI -> com.javierrb35.bibliotecaia.data.BookFormat.MOBI
        DetectedFormat.PDF -> throw ImportError.UnsupportedPdf(fileName)
        DetectedFormat.UNKNOWN -> throw ImportError.UnknownFormat(fileName)
    }

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK\u0003\u0004"
    private val PDF_MAGIC = "%PDF-".toByteArray(Charsets.US_ASCII)
    private const val MOBI_OFFSET = 60
    private const val SNIFF_SIZE = 72

    /** Lectura rápida de cabecera (no consume el resto si el llamante controla el stream). */
    fun detect(header: ByteArray): DetectedFormat {
        if (startsWith(header, PDF_MAGIC)) return DetectedFormat.PDF
        if (startsWith(header, ZIP_MAGIC)) {
            return if (isEpubHeader(header)) DetectedFormat.EPUB else DetectedFormat.UNKNOWN
        }
        if (header.size >= MOBI_OFFSET + 8 && String(header, MOBI_OFFSET, 8, Charsets.US_ASCII) == "BOOKMOBI") {
            return DetectedFormat.MOBI
        }
        return DetectedFormat.UNKNOWN
    }

    /**
     * Detección autoritativa sobre un stream independiente (por ejemplo, un archivo temporal
     * ya copiado): abre el ZIP y comprueba el recurso "mimetype" del EPUB.
     */
    fun detectFull(openStream: () -> InputStream): DetectedFormat =
        openStream().use { stream ->
            val header = ByteArray(SNIFF_SIZE)
            var read = 0
            while (read < header.size) {
                val n = stream.read(header, read, header.size - read)
                if (n < 0) break
                read += n
            }
            val partial = header.copyOf(read)
            when (detect(partial)) {
                DetectedFormat.PDF, DetectedFormat.MOBI -> detect(partial)
                else ->
                    // ZIP u UNKNOWN: validar estructura EPUB completa con un nuevo stream
                    if (EpubValidator.hasEpubMimetype(openStream)) DetectedFormat.EPUB
                    else DetectedFormat.UNKNOWN
            }
        }

    private fun isEpubHeader(header: ByteArray): Boolean {
        // Heurística rápida: en un EPUB bien formado, el primer recurso local es "mimetype"
        // (cabecera local de 30 bytes + nombre de 8), cuyo contenido es "application/epub+zip".
        // La validación autoritativa la hace EpubValidator leyendo el ZIP completo.
        val prefix = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        for (start in 30..70) {
            if (startsWithAt(header, start, prefix)) return true
        }
        return false
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean = startsWithAt(data, 0, prefix)

    private fun startsWithAt(data: ByteArray, offset: Int, prefix: ByteArray): Boolean {
        if (offset < 0 || data.size < offset + prefix.size) return false
        for (i in prefix.indices) {
            if (data[offset + i] != prefix[i]) return false
        }
        return true
    }
}

/** Comprobación EPUB autoritativa leyendo el recurso "mimetype" del ZIP. */
internal object EpubValidator {

    const val EPUB_MIMETYPE = "application/epub+zip"

    fun hasEpubMimetype(openStream: () -> InputStream): Boolean =
        try {
            openStream().use { stream ->
                BoundedZipInputStream(stream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    var checked = 0
                    while (entry != null && checked < 5) {
                        if (entry.name == "mimetype") {
                            val content = zip.readEntry(64)
                            return String(content, Charsets.US_ASCII).trim() == EPUB_MIMETYPE
                        }
                        checked++
                        entry = zip.nextEntry
                    }
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
}

/** Hash SHA-256 en streaming (libros pueden ser grandes; nunca cargar en memoria). */
object ContentHasher {

    fun sha256Hex(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
        var read = stream.read(buffer)
        while (read >= 0) {
            digest.update(buffer, 0, read)
            read = stream.read(buffer)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Deriva un título legible del nombre de archivo cuando faltan metadatos. */
object TitleSanitizer {

    fun fromFileName(fileName: String): String =
        fileName
            .replace(Regex("\\.[A-Za-z0-9]{1,5}$"), "")
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { fileName }
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }
}
