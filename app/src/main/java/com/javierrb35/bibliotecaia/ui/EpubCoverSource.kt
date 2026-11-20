package com.javierrb35.bibliotecaia.ui

import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Embedded raster cover only. Never extracts archive paths or follows external references. */
object EpubCoverSource {
    private const val XML_LIMIT = 512 * 1024
    private const val IMAGE_LIMIT = 8 * 1024 * 1024

    fun read(file: File): ByteArray? = try {
        ZipFile(file).use { zip ->
            if (zip.size() > 10000) return null
            val container = xml(readEntry(zip, "META-INF/container.xml", XML_LIMIT) ?: return null)
            val roots = container.getElementsByTagNameNS("*", "rootfile")
            if (roots.length == 0) return null
            val opfPath = resolve("", (roots.item(0) as Element).getAttribute("full-path")) ?: return null
            val opf = xml(readEntry(zip, opfPath, XML_LIMIT) ?: return null)
            val metadata = opf.getElementsByTagNameNS("*", "meta")
            var legacyId: String? = null
            for (i in 0 until metadata.length) {
                val node = metadata.item(i) as Element
                if (node.getAttribute("name") == "cover") legacyId = node.getAttribute("content")
            }
            val items = opf.getElementsByTagNameNS("*", "item")
            for (i in 0 until items.length) {
                val node = items.item(i) as Element
                val isCover = node.getAttribute("properties").split(Regex("\\s+")).contains("cover-image") ||
                    (legacyId != null && node.getAttribute("id") == legacyId)
                if (!isCover || node.getAttribute("media-type") !in setOf("image/jpeg", "image/png", "image/webp")) continue
                val path = resolve(opfPath.substringBeforeLast('/', ""), node.getAttribute("href")) ?: return null
                return readEntry(zip, path, IMAGE_LIMIT)
            }
            null
        }
    } catch (_: Exception) { null }

    private fun resolve(directory: String, href: String): String? {
        val uri = URI(href)
        if (uri.isAbsolute || uri.rawAuthority != null || uri.fragment != null || uri.query != null) return null
        val decoded = uri.path ?: return null
        if (decoded.startsWith('/') || decoded.contains('\\')) return null
        val parts = mutableListOf<String>()
        for (part in (if (directory.isEmpty()) decoded else "$directory/$decoded").split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/").takeIf { it.isNotBlank() }
    }

    private fun readEntry(zip: ZipFile, path: String, limit: Int): ByteArray? {
        val entry = zip.getEntry(path) ?: return null
        if (entry.isDirectory || entry.size > limit) return null
        return zip.getInputStream(entry).use { readLimited(it, limit) }
    }

    private fun readLimited(stream: InputStream, limit: Int): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun xml(bytes: ByteArray): org.w3c.dom.Document {
        // A decoded Reader prevents an XML encoding declaration from bypassing this gate.
        val text = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(!text.contains('\u0000') && !text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true))
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw org.xml.sax.SAXException("External entities prohibited") }
        return builder.parse(org.xml.sax.InputSource(java.io.StringReader(text)))
    }
}
