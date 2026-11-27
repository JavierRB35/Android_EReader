package com.javierrb35.bibliotecaia.importer

import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Metadatos mínimos extraídos en la importación inicial. */
data class ExtractedMetadata(
    val title: String?,
    val authors: String?
)

/**
 * Parser EPUB mínimo y sin dependencias: container.xml → OPF → dc:title / dc:creator.
 * No presupone estructura interna concreta del libro; si falta el OPF devuelve nulos
 * y el importador usará el nombre de archivo (Prompt Maestro §8.6.5).
 */
object EpubMetadataParser {

    fun parse(openStream: () -> InputStream): ExtractedMetadata? = try {
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
                                return ExtractedMetadata(
                                    title = firstText(doc, "title"),
                                    authors = creatorList(doc)
                                )
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

    private fun firstText(doc: org.w3c.dom.Document, localName: String): String? {
        val nodes = doc.getElementsByTagName("*")
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.localName == localName && isDublinCore(el)) {
                return el.textContent?.trim()?.ifEmpty { null }
            }
        }
        return null
    }

    private fun creatorList(doc: org.w3c.dom.Document): String? {
        val nodes = doc.getElementsByTagName("*")
        val authors = mutableListOf<String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            if (el.localName == "creator" && isDublinCore(el)) {
                el.textContent?.trim()?.takeIf { it.isNotEmpty() }?.let { authors.add(it) }
            }
        }
        return if (authors.isEmpty()) null else authors.joinToString(", ")
    }
}

/**
 * Parser MOBI/PDB mínimo: nombre del libro en la cabecera PDB (offset 0, hasta 32 bytes,
 * terminado en NUL). La validación de magic "BOOKMOBI" ya la hace BookFormatDetector.
 */
object MobiMetadataParser {

    const val PDB_HEADER_SIZE = 78

    fun parse(header: ByteArray): ExtractedMetadata? {
        if (header.size < PDB_HEADER_SIZE) return null
        val nameBytes = header.copyOfRange(0, 32)
        val nameLength = nameBytes.indexOfFirst { it == 0.toByte() }.let { if (it < 0) nameBytes.size else it }
        val name = String(nameBytes, 0, nameLength, Charsets.ISO_8859_1).trim()
        return if (name.isEmpty()) null else ExtractedMetadata(title = name, authors = null)
    }
}
