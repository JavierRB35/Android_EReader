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

    data class Fragment(
        val index: Int, val title: String, val text: String,
        // Optional fractions of the full normalized source. MOBI is split
        // into windows so a partial reading cannot expose later windows.
        val startFraction: Double? = null,
        val endFraction: Double? = null,
    )

    const val MAX_FRAGMENT_CHARS = 6_000
    const val MAX_TOTAL_CHARS = 40_000

    fun chapters(file: File, format: String, ocrDir: File?): List<Fragment> = when (format) {
        "EPUB" -> epubChapters(file)
        "MOBI" -> try {
            // Use the same readable text the reader presents, not HTML markup.
            // A fragment is capped before it enters the RAG retrieval pipeline.
            val text = stripTags(MobiDocument.readHtml(file))
            if (text.isBlank()) emptyList()
            else {
                val windows = if (text.length <= MAX_TOTAL_CHARS) {
                    (text.indices step MAX_FRAGMENT_CHARS).map { start ->
                        start to text.substring(start, minOf(start + MAX_FRAGMENT_CHARS, text.length))
                    }
                } else {
                    // Bounded coverage of a long MOBI: do not silently restrict
                    // book synopsis and search to the first 40,000 characters.
                    val count = MAX_TOTAL_CHARS / MAX_FRAGMENT_CHARS
                    (0 until count).map { slot ->
                        val start = ((text.length - MAX_FRAGMENT_CHARS).toLong() * slot /
                            (count - 1)).toInt()
                        start to text.substring(start, start + MAX_FRAGMENT_CHARS)
                    }
                }
                windows.mapIndexed { index, (start, window) ->
                    val end = start + window.length
                    Fragment(index + 1, file.nameWithoutExtension + " · parte " + (index + 1),
                        window, start.toDouble() / text.length, end.toDouble() / text.length)
                }
            }
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
        // Numerical page order is essential to the progress-based spoiler filter.
        return ocrDir.listFiles()?.mapNotNull { page ->
            if (!page.isFile || !Regex("""page-\d+\.txt""").matches(page.name) ||
                page.length() !in 1L..64_000L) return@mapNotNull null
            val number = page.name.removePrefix("page-").removeSuffix(".txt").toIntOrNull()
                ?: return@mapNotNull null
            val text = runCatching { page.readText(Charsets.UTF_8).trim() }.getOrNull()
                ?: return@mapNotNull null
            if (text.isBlank()) null else Fragment(number, "Página $number",
                text.take(MAX_FRAGMENT_CHARS))
        }?.sortedBy { it.index } ?: emptyList()
    }

    // ---------- selección RAG ----------

    /**
     * A blank book-level question is a request for a synopsis, not keyword RAG.
     * With spoilers disabled, sample the beginning, middle and end instead of
     * only the first three search hits. With spoilers enabled, use only text
     * the reader has reached; at 0% use a short opening preview for a useful
     * introductory synopsis without revealing later chapters.
     *
     * The source is a bounded representative sample, not a claim to have
     * processed every word of a long book.
     */
    fun overview(
        chapters: List<Fragment>, spoilerSafe: Boolean, progress: Float,
        maxFragments: Int = 5
    ): List<Fragment> {
        if (chapters.isEmpty() || maxFragments <= 0) return emptyList()
        val count = maxFragments.coerceAtMost(6)
        if (spoilerSafe) {
            val p = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
            if (p <= 0f) {
                val first = chapters.first()
                return listOf(first.copy(text = first.text.take(1800)))
                    .filter { it.text.isNotBlank() }
            }
            if (chapters.any { it.startFraction != null }) {
                return retrieve(chapters, "", true, p, count)
            }
            val allowed = (p * chapters.size).toInt().coerceIn(1, chapters.size)
            return chapters.take(allowed).take(count)
                .map { it.copy(text = it.text.take(MAX_FRAGMENT_CHARS)) }
        }
        val size = minOf(chapters.size, count)
        val indices = if (size == 1) listOf(0) else
            (0 until size).map { it * chapters.lastIndex / (size - 1) }.distinct()
        return indices.map { chapters[it].let { fragment ->
            fragment.copy(text = fragment.text.take(MAX_FRAGMENT_CHARS))
        } }
    }

    /**
     * Recuperación de fragmentos relevantes sin embeddings: puntuación por
     * coincidencia de palabras de la pregunta con el texto, y recorte por
     * anti-spoilers según el progreso de lectura (aproximación honesta).
     */
    fun retrieve(chapters: List<Fragment>, question: String, spoilerSafe: Boolean,
                 progress: Float, maxFragments: Int = 3): List<Fragment> {
        var candidates = chapters
        if (spoilerSafe) {
            val p = if (progress.isFinite()) progress.coerceIn(0f, 1f).toDouble() else 0.0
            if (chapters.any { it.startFraction != null }) {
                // Unlike an arbitrary chapter boundary, a MOBI window has
                // an explicit position within the normalized source. The
                // final window is trimmed so its unseen tail cannot leak.
                candidates = chapters.mapNotNull { fragment ->
                    val start = fragment.startFraction ?: return@mapNotNull null
                    val end = fragment.endFraction ?: return@mapNotNull null
                    val readable = when {
                        p <= start -> 0
                        p >= end -> fragment.text.length
                        else -> ((p - start) / (end - start) * fragment.text.length)
                            .toInt().coerceIn(0, fragment.text.length)
                    }
                    fragment.copy(text = fragment.text.take(readable))
                        .takeIf { it.text.isNotBlank() }
                }
            } else {
                // EPUB/PDF chapter and OCR-page indices are only an
                // approximation of reading progress. Never include a later
                // known chapter than the conservative progress boundary.
                val readable = when {
                    p <= 0.0 -> 0
                    else -> (p * chapters.size).toInt().coerceAtLeast(1)
                }
                candidates = chapters.take(readable)
            }
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
