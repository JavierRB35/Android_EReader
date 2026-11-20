package com.javierrb35.bibliotecaia.ai

import com.javierrb35.bibliotecaia.reader.mobi.MobiDocument
import java.io.File
import java.util.zip.ZipFile

/**
 * Extracción de texto de libros para RAG (Prompt Maestro §12.4): fragmentos
 * delimitados y numerables, con títulos citables. Sin enviar el binario completo.
 *
 * EPUB: capítulos en orden de spine (manifest→itemref), título de <title>/<h1..h3>.
 * MOBI: un único fragmento con el texto extraído (sin índice interno fiable).
 * PDF: sin extracción de texto en V1 — la IA sobre PDF usa el texto OCR almacenado
 * en filesDir/ocr/<bookId>/page-N.txt si existe (Incremento OCR).
 */
object BookTextSource {

    data class Fragment(val index: Int, val title: String, val text: String)

    const val MAX_FRAGMENT_CHARS = 6_000
    const val MAX_TOTAL_CHARS = 40_000

    fun chapters(file: File, format: String, ocrDir: File?): List<Fragment> = when (format) {
        "EPUB" -> epubChapters(file).ifEmpty { listOf(Fragment(1, file.name, readWhole(file))) }
        "MOBI" -> try {
            listOf(Fragment(1, file.nameWithoutExtension, MobiDocument.readHtml(file)))
        } catch (_: Exception) {
            emptyList()
        }
        "PDF" -> ocrPages(ocrDir).ifEmpty { emptyList() }
        else -> emptyList()
    }

    // ---------- EPUB ----------

    private fun epubChapters(file: File): List<Fragment> = try {
        ZipFile(file).use { zip ->
            if (zip.size() > 10_000) return emptyList()
            val opfPath = containerRootfile(zip) ?: return emptyList()
            val opf = readText(zip, opfPath) ?: return emptyList()
            val manifest = Regex("""<manifest\b.*?</manifest>""", RegexOption.DOT_MATCHES_ALL).find(opf)?.value ?: return emptyList()
            val spine = Regex("""<spine\b.*?</spine>""", RegexOption.DOT_MATCHES_ALL).find(opf)?.value ?: return emptyList()
            val hrefs = Regex("""<item\b[^>]*\bid="([^"]+)"[^>]*\bhref="([^"]+)"[^>]*>""").findAll(manifest)
                .associate { it.groupValues[1] to it.groupValues[2] }
            val order = Regex("""<itemref\b[^>]*\bidref="([^"]+)"[^>]*>""").findAll(spine)
                .mapNotNull { hrefs[it.groupValues[1]] }.toList()
            if (order.isEmpty()) return emptyList()

            val base = opfPath.substringBeforeLast('/', "")
            var index = 0
            order.mapNotNull { href ->
                val path = resolve(base, href) ?: return@mapNotNull null
                val raw = readText(zip, path) ?: return@mapNotNull null
                val title = Regex("""<title[^>]*>(.*?)</title>""", RegexOption.DOT_MATCHES_ALL)
                    .find(raw)?.groupValues?.get(1)?.trim().orEmpty()
                    .ifEmpty { "Capítulo ${index + 1}" }
                val text = stripTags(raw).take(MAX_FRAGMENT_CHARS)
                if (text.isBlank()) return@mapNotNull null
                index++
                Fragment(index, title, text)
            }
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun resolve(directory: String, href: String): String? {
        if (href.contains(":") || href.startsWith("/") || href.contains("\\")) return null
        val parts = mutableListOf<String>()
        (if (directory.isEmpty()) href else "$directory/$href").split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun containerRootfile(zip: ZipFile): String? {
        val entry = zip.getEntry("META-INF/container.xml") ?: return null
        val doc = readText(zip, "META-INF/container.xml") ?: return null
        return Regex("""full-path="([^"]+)"""").find(doc)?.groupValues?.get(1)
    }

    private fun readText(zip: ZipFile, path: String): String? = try {
        zip.getEntry(path)?.let { entry ->
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            if (bytes.size > 2_000_000) null else String(bytes, Charsets.UTF_8)
        }
    } catch (_: Exception) {
        null
    }

    /** Retira etiquetas y descodifica entidades básicas; sin ejecutar nada. */
    fun stripTags(html: String): String = html
        .replace(Regex("""<(script|style)\b.*?</\1>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        .replace(Regex("""<[^>]+>"""), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("""\s+"""), " ")
        .trim()

    // ---------- OCR (PDF escaneados) ----------

    private fun ocrPages(ocrDir: File?): List<Fragment> {
        if (ocrDir == null || !ocrDir.isDirectory) return emptyList()
        return ocrDir.listFiles { f -> f.name.startsWith("page-") && f.name.endsWith(".txt") }
            ?.sortedBy { it.name }
            ?.mapNotNull { page ->
                val number = Regex("""page-(\d+)""").find(page.name)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
                val text = page.readText(Charsets.UTF_8).trim()
                if (text.isBlank()) null else Fragment(number, "Página $number", text.take(MAX_FRAGMENT_CHARS))
            }
            ?: emptyList()
    }

    // ---------- selección RAG ----------

    /**
     * Recuperación de fragmentos relevantes sin embeddings: puntuación por
     * coincidencia de palabras de la pregunta con el texto, y recorte por
     * anti-spoilers según el progreso de lectura (aproximación honesta).
     */
    fun retrieve(chapters: List<Fragment>, question: String, spoilerSafe: Boolean,
                 progress: Float, maxFragments: Int = 3): List<Fragment> {
        var candidates = chapters
        if (spoilerSafe) {
            val readable = when {
                progress <= 0f -> 1
                else -> (progress * chapters.size).toInt().coerceAtLeast(1)
            }
            candidates = chapters.take(readable)
        }
        val stop = setOf("que", "del", "los", "las", "una", "por", "para", "con", "como",
            "más", "cual", "cuál", "quién", "quien", "qué", "donde", "dónde", "este", "esta",
            "sobre", "libro", "autor", "dice", "dice?", "según")
        val terms = question.lowercase().split(Regex("""[\W_]+""")).filter { it.length > 2 && it !in stop }
        val scored = candidates.map { chapter ->
            val lower = chapter.text.lowercase()
            chapter to terms.count { term -> lower.contains(term) }
        }
        val selected = if (terms.isEmpty()) candidates else
            scored.sortedByDescending { it.second }.filter { it.second > 0 }.map { it.first }
                .ifEmpty { candidates }
        return selected.take(maxFragments).map { it.copy(text = it.text.take(MAX_FRAGMENT_CHARS)) }
            .sortedBy { it.index }
    }

    /** Lectura completa (MOBI) recortada al máximo de contexto enviado. */
    private fun readWhole(file: File): String = try {
        stripTags(MobiDocument.readHtml(file)).take(MAX_TOTAL_CHARS)
    } catch (_: Exception) {
        ""
    }
}
