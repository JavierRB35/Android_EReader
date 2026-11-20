@file:OptIn(org.readium.r2.shared.ExperimentalReadiumApi::class)

package com.javierrb35.bibliotecaia.reader.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.getOrElse
import java.io.IOException

/**
 * Native TextView reading surface. Unlike Readium's WebView selection, Android
 * owns the gesture, handles, magnifier and selection ActionMode exactly as in
 * Scriptura Android. The publication remains Readium-owned and unchanged.
 *
 * Only reflowable XHTML text is rendered here. Readers can switch to the
 * original Readium layout for images, tables and complex typography.
 */
internal object NativeEpubText {
    data class Chapter(val href: String, val title: String, val text: String)

    private const val MAX_SOURCE_BYTES = 4 * 1024 * 1024
    private const val MAX_TEXT_CHARS = 700_000
    private val breaks = setOf(
        "p", "div", "section", "article", "header", "footer", "aside",
        "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6",
        "li", "ul", "ol", "dl", "dt", "dd", "table", "tr"
    )

    suspend fun load(publication: Publication, link: Link): Chapter =
        withContext(Dispatchers.IO) {
            val resource = publication.get(link.url())
                ?: throw IOException("No se encontró este capítulo en el EPUB.")
            val bytes = try {
                resource.read().getOrElse {
                    throw IOException("No se pudo leer el capítulo de la publicación.")
                }
            } finally {
                resource.close()
            }
            require(bytes.size <= MAX_SOURCE_BYTES) {
                "El capítulo es demasiado grande para la selección nativa."
            }
            val document = Jsoup.parse(bytes.inputStream(), null, "")
            document.select("script,style,iframe,object,embed,svg,form").remove()
            val body = document.body()
            val title = link.title?.takeIf(String::isNotBlank)
                ?: document.title().takeIf(String::isNotBlank)
                ?: "Capítulo"
            val text = readableText(body)
            require(text.isNotBlank()) {
                "Este capítulo no tiene texto seleccionable. Utiliza Maquetación."
            }
            Chapter(link.url().toString(), title, text)
        }

    /** Deterministic normalization: offsets remain stable after reopening. */
    internal fun readableText(root: Element): String {
        val result = StringBuilder()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (result.length > MAX_TEXT_CHARS) return
                when (node) {
                    is TextNode -> {
                        val value = node.text().replace(Regex("[\\t\\r\\n ]+"), " ")
                        if (value.isNotEmpty()) {
                            if (result.isNotEmpty() && !result.last().isWhitespace() &&
                                !value.first().isWhitespace()) result.append(' ')
                            result.append(value)
                        }
                    }
                    is Element -> when (node.normalName()) {
                        "br" -> result.append('\n')
                        "hr" -> result.append("\n\n")
                        "li" -> result.append("\n• ")
                        else -> if (node.normalName() in breaks && result.isNotEmpty() &&
                            result.last() != '\n') result.append("\n\n")
                    }
                }
            }
            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in breaks &&
                    result.isNotEmpty() && result.last() != '\n') result.append("\n\n")
            }
        }, root)
        require(result.length <= MAX_TEXT_CHARS) { "El capítulo supera el límite de texto nativo." }
        return result.toString()
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
