package com.javierrb35.bibliotecaia.importer

import com.javierrb35.bibliotecaia.data.BookFormat
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Metadatos mínimos extraídos en la importación inicial. */
data class ExtractedMetadata(
    val title: String?,
    val authors: String?
)

/**
 * Metadatos internos completos para la reparación de fichas (Issue #155):
 * título, autores, etiquetas y argumento tal y como constan en el propio
 * archivo. Nunca inventa campos ausentes.
 */
data class RichMetadata(
    val title: String?,
    val authors: List<String>,
    val tags: List<String>,
    val description: String?,
) {
    val authorsJoined: String? get() = authors.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** Punto de entrada único por formato para la reparación de fichas existentes. */
object BookRichMetadataParser {
    fun parse(format: BookFormat, file: File): RichMetadata? = try {
        when (format) {
            BookFormat.EPUB -> EpubMetadataParser.parseRich { file.inputStream() }
            BookFormat.MOBI -> MobiMetadataParser.parseRich(file)
            BookFormat.PDF -> null // histórico sin lector ni metadatos en el alcance actual
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Parser EPUB mínimo y sin dependencias: container.xml → OPF → dc:title / dc:creator.
 * No presupone estructura interna concreta del libro; si falta el OPF devuelve nulos
 * y el importador usará el nombre de archivo (Prompt Maestro §8.6.5).
 */
object EpubMetadataParser {

    fun parse(openStream: () -> InputStream): ExtractedMetadata? =
        parseRich(openStream)?.let {
            ExtractedMetadata(it.title, it.authorsJoined)
        }

    /** Misma traversal segura que parse(), ampliado con etiquetas y argumento (#155). */
    fun parseRich(openStream: () -> InputStream): RichMetadata? = try {
        openStream().use { stream ->
            BoundedZipInputStream(stream.buffered()).use { zip ->
                var opfPath: String? = null
                var entry = zip.nextEntry
                while (entry != null && opfPath == null) {
                    if (entry.name == "META-INF/container.xml") {
                        // Copiar la entrada a memoria: el parser DOM cierra el stream que recibe
                        // (Xerces) y cerraría el ZipInputStream en pleno recorrido.
                        val doc = newDocument(zip.readEntry(1024 * 1024).inputStream())
                        val rootFiles = doc.getElementsByTagName("rootfile")
                        for (i in 0 until rootFiles.length) {
                            val el = rootFiles.item(i) as Element
                            val fullPath = el.getAttribute("full-path")
                            if (fullPath.isNotBlank()) {
                                opfPath = fullPath
                                break
                            }
                        }
                    }
                    entry = zip.nextEntry
                }

                if (opfPath == null) return null

                // Segunda pasada: leer el OPF localizado
                openStream().use { stream2 ->
                    BoundedZipInputStream(stream2.buffered()).use { zip2 ->
                        var e = zip2.nextEntry
                        while (e != null) {
                            if (e.name == opfPath) {
                                val doc = newDocument(zip2.readEntry(4 * 1024 * 1024).inputStream())
                                val authors = mutableListOf<String>()
                                val tags = mutableListOf<String>()
                                val nodes = doc.getElementsByTagName("*")
                                var title: String? = null
                                var description: String? = null
                                for (i in 0 until nodes.length) {
                                    val el = nodes.item(i) as Element
                                    if (!isDublinCore(el)) continue
                                    val text = el.textContent?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                                    when (el.localName) {
                                        "title" -> if (title == null) title = text
                                        "creator" -> authors.add(text)
                                        "subject" -> tags.add(text)
                                        "description" -> if (description == null) description = text
                                    }
                                }
                                return RichMetadata(title, authors, tags, description)
                            }
                            e = zip2.nextEntry
                        }
                        null
                    }
                }
            }
        }
    } catch (_: Exception) {
        null // metadatos ausentes o malformados: no bloquea la importación
    }

    private fun newDocument(stream: InputStream): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        try {
            // Disminuir superficie XXE: rechazar DTD en contenido de terceros (protocolo §16)
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (_: Exception) {
            // parser sin soporte de la feature: continúa (contenido local validado por ZIP)
        }
        val bytes = stream.readBytes()
        val markup = String(bytes, Charsets.ISO_8859_1)
        require(!markup.contains("<!DOCTYPE", true) && !markup.contains("<!ENTITY", true))
        return factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities disabled") }
        }.parse(bytes.inputStream())
    }

    private fun isDublinCore(el: Element) = el.namespaceURI?.startsWith("http://purl.org/dc/") == true
}

/**
 * Parser MOBI/PDB mínimo: nombre del libro en la cabecera PDB (offset 0, hasta 32 bytes,
 * terminado en NUL). La validación de magic "BOOKMOBI" ya la hace BookFormatDetector.
 */
object MobiMetadataParser {

    const val PDB_HEADER_SIZE = 78
    private const val LIMIT = 8 * 1024 * 1024

    fun parse(header: ByteArray): ExtractedMetadata? {
        if (header.size < PDB_HEADER_SIZE) return null
        val nameBytes = header.copyOfRange(0, 32)
        val nameLength = nameBytes.indexOfFirst { it == 0.toByte() }.let { if (it < 0) nameBytes.size else it }
        val name = String(nameBytes, 0, nameLength, Charsets.ISO_8859_1).trim()
        return if (name.isEmpty()) null else ExtractedMetadata(title = name, authors = null)
    }

    /**
     * Metadatos completos EXTH para reparación (#155), con la misma traversal
     * acotada de [com.javierrb35.bibliotecaia.ui.MobiCoverSource]: sin descompresión
     * de texto ni DRM; solo registros de texto identificados.
     */
    fun parseRich(file: File): RichMetadata? = try {
        RandomAccessFile(file, "r").use { input ->
            val length = file.length()
            if (length < 94) return null
            input.seek(60)
            if (String(ByteArray(8).also(input::readFully), Charsets.US_ASCII) != "BOOKMOBI") return null
            input.seek(76)
            val count = input.readUnsignedShort()
            val tableEnd = 78L + count * 8L
            if (count < 2 || tableEnd > length) return null
            val offsets = LongArray(count + 1)
            repeat(count) { i ->
                input.seek(78L + i * 8)
                offsets[i] = input.readInt().toLong() and 0xffffffffL
                if (offsets[i] < tableEnd || offsets[i] >= length ||
                    (i > 0 && offsets[i] <= offsets[i - 1])) return null
            }
            offsets[count] = length
            fun record(i: Int): ByteArray? {
                if (i !in 0 until count) return null
                val size = offsets[i + 1] - offsets[i]
                if (size !in 1L..LIMIT.toLong()) return null
                input.seek(offsets[i])
                return ByteArray(size.toInt()).also(input::readFully)
            }
            val header = record(0) ?: return null
            fun number(at: Int): Long {
                require(at >= 0 && at <= header.size - 4)
                return (0..3).fold(0L) { result, i -> (result shl 8) or (header[at + i].toLong() and 255L) }
            }
            if (header.size < 132 || header[12].toInt() != 0 || header[13].toInt() != 0 ||
                String(header, 16, 4, Charsets.US_ASCII) != "MOBI") return null
            val headerSize = number(20)
            if (headerSize < 116 || headerSize > header.size - 16L) return null
            val encoding = if (number(28) == 65001L) Charsets.UTF_8 else Charsets.ISO_8859_1
            fun text(bytes: ByteArray): String = bytes.toString(encoding).trim('\u0000').trim()
            val nameOffset = number(84).toInt()
            val nameLength = number(88).toInt()
            val fallbackTitle = if (nameOffset in header.indices &&
                nameOffset + nameLength <= header.size && nameLength > 0)
                text(header.copyOfRange(nameOffset, nameOffset + nameLength)) else ""
            val exth = (16 + headerSize).toInt()
            if (exth > header.size - 12 || String(header, exth, 4, Charsets.US_ASCII) != "EXTH") {
                return fallbackTitle.takeIf { it.isNotEmpty() }
                    ?.let { RichMetadata(it, emptyList(), emptyList(), null) }
            }
            val exthSize = number(exth + 4)
            val records = number(exth + 8)
            if (exthSize < 12 || exthSize > header.size - exth || records > (exthSize - 12) / 8) return null
            var at = exth + 12
            val end = exth + exthSize.toInt()
            val authors = mutableListOf<String>()
            val tags = mutableListOf<String>()
            var description: String? = null
            var updatedTitle: String? = null
            repeat(records.toInt()) {
                if (at > end - 8) return null
                val type = number(at)
                val size = number(at + 4)
                if (size < 8 || size > end - at) return null
                if (size >= 12) {
                    val payload = header.copyOfRange(at + 8, at + size.toInt())
                    when (type) {
                        100L -> text(payload).takeIf { it.isNotEmpty() }?.let(authors::add)
                        103L -> if (description == null) description = text(payload).ifEmpty { null }
                        105L -> text(payload).takeIf { it.isNotEmpty() }?.let(tags::add)
                        503L -> if (updatedTitle == null) updatedTitle = text(payload).ifEmpty { null }
                    }
                }
                at += size.toInt()
            }
            RichMetadata(updatedTitle ?: fallbackTitle.ifEmpty { null }, authors, tags, description)
        }
    } catch (_: Exception) {
        null
    }
}
