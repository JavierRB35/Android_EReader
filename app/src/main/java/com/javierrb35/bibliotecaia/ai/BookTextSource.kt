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
        "EPUB" -> epubChapters(file)
        "MOBI" -> try {
            // Use the same readable text the reader presents, not HTML markup.
            // A fragment is capped before it enters the RAG retrieval pipeline.
            val text = stripTags(MobiDocument.readHtml(file)).take(MAX_FRAGMENT_CHARS)
            if (text.isBlank()) emptyList()
            else listOf(Fragment(1, file.nameWithoutExtension, text))
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
            // XML attributes are unordered and may use either quote style.
            // Keep the order from the spine, not the order of manifest items.
            val hrefs = Regex("""<item\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(manifest)
                .mapNotNull { match ->
                    val id = xmlAttribute(match.value, "id")?.takeIf { it.isNotBlank() }
                    val href = xmlAttribute(match.value, "href")?.takeIf { it.isNotBlank() }
                    if (id == null || href == null) null else id to href
                }.toMap()
            val order = Regex("""<itemref\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(spine)
                .mapNotNull { match -> xmlAttribute(match.value, "idref")?.let(hrefs::get) }.toList()
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

    /** Only simple, quoted XML attributes are read; never resolve external entities. */
    private fun xmlAttribute(tag: String, name: String): String? =
        Regex("""\s${Regex.escape(name)}\s*=\s*(['"])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)
            .find(tag)?.groupValues?.get(2)

    private fun containerRootfile(zip: ZipFile): String? {
        val doc = readText(zip, "META-INF/container.xml") ?: return null
        val tag = Regex("""<rootfile\b[^>]*>""", RegexOption.IGNORE_CASE)
            .find(doc)?.value ?: return null
        val path = xmlAttribute(tag, "full-path") ?: return null
        return path.takeIf { resolve("", it) == it }
    }

    /**
     * Strict decompressed-byte cap, including ZIP entries whose header size is
     * missing/incorrect. Never call readBytes() on untrusted EPUB entries.
     */
    private fun readText(zip: ZipFile, path: String): String? = try {
        zip.getEntry(path)?.let { entry ->
            if (entry.isDirectory || entry.size > MAX_EPUB_ENTRY_BYTES) null
            else {
                val output = java.io.ByteArrayOutputStream()
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() > MAX_EPUB_ENTRY_BYTES - count) return null
                        output.write(buffer, 0, count)
                    }
                }
                output.toString(Charsets.UTF_8.name())
            }
        }
    } catch (_: Exception) {
        null
    }

    private const val MAX_EPUB_ENTRY_BYTES = 2_000_000

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

}
