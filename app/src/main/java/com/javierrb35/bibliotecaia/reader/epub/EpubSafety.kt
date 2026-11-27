package com.javierrb35.bibliotecaia.reader.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Entities
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

/** Bounds are applied to actual inflated bytes, not only to attacker-controlled ZIP metadata. */
object EpubSafety {
    private const val MAX_ENTRIES = 10000
    private const val MAX_ENTRY = 32L * 1024 * 1024
    private const val MAX_TEXT = 4L * 1024 * 1024
    private const val MAX_TOTAL = 256L * 1024 * 1024

    fun validate(file: File) {
        ZipFile(file).use { zip ->
            require(zip.size() <= MAX_ENTRIES) { "El EPUB contiene demasiados archivos." }
            var total = 0L
            val names = HashSet<String>()
            val buffer = ByteArray(32768)
            zip.entries().asSequence().forEach { entry ->
                val name = entry.name
                require(!name.startsWith('/') && !name.contains('\\') &&
                    name.split('/').none { it == ".." } && !name.contains(':') && names.add(name)) {
                    "El EPUB contiene rutas inseguras o duplicadas."
                }
                if (!entry.isDirectory) {
                    val limit = if (isMarkup(name) || name.endsWith(".css", true)) MAX_TEXT else MAX_ENTRY
                    require(entry.size <= limit) { "Un recurso del EPUB supera el límite seguro." }
                    var size = 0L
                    zip.getInputStream(entry).use { input ->
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw IOException("Lectura cancelada")
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            total += count
                            require(size <= limit && total <= MAX_TOTAL) { "El EPUB descomprimido supera el límite seguro." }
                        }
                    }
                }
            }
        }
    }

    fun isMarkup(path: String): Boolean = path.substringBefore('?').lowercase(Locale.ROOT).let {
        it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") || it.endsWith(".svg")
    }

    /** Book scripts and remote loads are not needed for ordinary reading. Original ZIP is untouched. */
    fun sanitize(data: ByteArray, svg: Boolean = false): ByteArray {
        require(data.size <= MAX_TEXT) { "Capítulo demasiado grande." }
        val document = if (svg) Jsoup.parse(String(data, Charsets.UTF_8), "", Parser.xmlParser())
            else Jsoup.parse(data.inputStream(), null, "")
        document.select("script, iframe, frame, frameset, object, embed, form, input, button, textarea, base, foreignObject, animate, set, animateMotion, animateTransform, discard").remove()
        if (svg) document.select("style").remove()
        document.select("meta[http-equiv]").remove()
        // Readium uses WebView's browser selection when the original layout
        // is enabled. Replace the default bright cyan with subdued warm amber.
        // The native Scriptura-style TextView has its own highlight color.
        if (!svg) document.head().appendElement("style")
            .attr("data-biblioteca-selection", "amber")
            .text("::selection { background-color: rgba(212,180,131,0.32) !important; " +
                  "color: inherit !important; }")
        for (element in document.allElements) {
            for (attribute in element.attributes().asList()) {
                val name = attribute.key.lowercase(Locale.ROOT)
                val value = attribute.value.filterNot { it.isWhitespace() || it.code < 32 }.lowercase(Locale.ROOT)
                if (name.startsWith("on") || name == "srcdoc" || (svg && name == "style") ||
                    (svg && name in setOf("href", "xlink:href", "src") && !value.startsWith('#')) ||
                    ((name in setOf("href", "src", "xlink:href", "action", "formaction")) &&
                        (value.startsWith("javascript:") || value.startsWith("vbscript:")))) {
                    element.removeAttr(attribute.key)
                }
            }
        }
        if (!svg) {
            // Readium injects its trusted scripts after this transform. All book scripts were removed.
            document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy")
                .attr("content", "default-src 'none'; script-src https://readium 'unsafe-inline'; " +
                    "style-src https://readium 'unsafe-inline'; img-src https://readium data:; " +
                    "font-src https://readium data:; media-src https://readium; connect-src 'none'; " +
                    "frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'")
        }
        document.outputSettings().charset(Charsets.UTF_8).prettyPrint(false)
            .syntax(Document.OutputSettings.Syntax.xml).escapeMode(Entities.EscapeMode.xhtml)
        return document.outerHtml().toByteArray(Charsets.UTF_8)
    }
}
