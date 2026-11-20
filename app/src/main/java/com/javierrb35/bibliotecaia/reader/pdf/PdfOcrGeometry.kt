package com.javierrb35.bibliotecaia.reader.pdf

import org.json.JSONArray
import org.json.JSONObject

/** Normalized coordinates, independent of PDF render size, zoom and pan. */
data class PdfOcrRect(
    val left: Float, val top: Float, val right: Float, val bottom: Float
) {
    fun valid() = listOf(left, top, right, bottom).all { it.isFinite() && it in 0f..1f } &&
        right > left && bottom > top
}

data class PdfOcrWord(val start: Int, val end: Int, val rect: PdfOcrRect)

/** Exact local OCR transcript plus original bitmap-relative word coordinates. */
data class PdfOcrSnapshot(
    val text: String,
    val contentHash: String,
    val words: List<PdfOcrWord>
)

object PdfOcrGeometry {
    const val MAX_TEXT = 16_000
    private const val MAX_WORDS = 4_096

    /**
     * Input lines follow the recognizer reading order. A word's UTF-16 offset
     * and rectangle are recorded together; repeated words never use indexOf().
     */
    fun fromLines(
        lines: List<List<Pair<String, PdfOcrRect>>>, contentHash: String
    ): PdfOcrSnapshot? {
        if (contentHash.isBlank() || contentHash.length > 128) return null
        val output = StringBuilder()
        val boxes = mutableListOf<PdfOcrWord>()
        loop@ for (line in lines) {
            val usable = line.filter { it.first.isNotBlank() && it.second.valid() }
            if (usable.isEmpty()) continue
            for ((i, entry) in usable.withIndex()) {
                val word = entry.first.trim()
                if (word.isEmpty()) continue
                val separator = if (i > 0) " " else if (output.isNotEmpty()) "\n" else ""
                if (boxes.size >= MAX_WORDS ||
                    output.length + separator.length + word.length > MAX_TEXT) break@loop
                output.append(separator)
                val start = output.length
                output.append(word)
                boxes.add(PdfOcrWord(start, output.length, entry.second))
            }
        }
        return PdfOcrSnapshot(output.toString(), contentHash, boxes)
            .takeIf { it.text.isNotBlank() && valid(it) }
    }

    fun boxesForRange(snapshot: PdfOcrSnapshot, start: Int, end: Int): List<PdfOcrRect> {
        if (!valid(snapshot) || start !in 0 until snapshot.text.length ||
            end > snapshot.text.length || end <= start) return emptyList()
        return snapshot.words.filter { it.end > start && it.start < end }.map { it.rect }
    }

    fun valid(snapshot: PdfOcrSnapshot): Boolean =
        snapshot.text.isNotBlank() && snapshot.text.length <= MAX_TEXT &&
        snapshot.contentHash.isNotBlank() && snapshot.contentHash.length <= 128 &&
        snapshot.words.size in 1..MAX_WORDS &&
        snapshot.words.all {
            it.start >= 0 && it.end <= snapshot.text.length && it.end > it.start && it.rect.valid()
        } && snapshot.words.zipWithNext().all { (first, second) -> second.start >= first.end }

    fun encode(snapshot: PdfOcrSnapshot): String {
        require(valid(snapshot))
        val words = JSONArray()
        snapshot.words.forEach { word ->
            words.put(JSONArray().put(word.start).put(word.end)
                .put(word.rect.left.toDouble()).put(word.rect.top.toDouble())
                .put(word.rect.right.toDouble()).put(word.rect.bottom.toDouble()))
        }
        return JSONObject().put("version", 1).put("contentHash", snapshot.contentHash)
            .put("text", snapshot.text).put("words", words).toString()
    }

    fun decode(json: String, expectedHash: String): PdfOcrSnapshot? = runCatching {
        if (json.length > 450_000) return@runCatching null
        val objectValue = JSONObject(json)
        if (objectValue.optInt("version", -1) != 1 ||
            objectValue.optString("contentHash") != expectedHash) return@runCatching null
        val text = objectValue.getString("text")
        val entries = objectValue.getJSONArray("words")
        if (entries.length() !in 1..MAX_WORDS || text.length > MAX_TEXT) return@runCatching null
        val words = (0 until entries.length()).map { i ->
            val a = entries.getJSONArray(i)
            require(a.length() == 6)
            PdfOcrWord(a.getInt(0), a.getInt(1),
                PdfOcrRect(a.getDouble(2).toFloat(), a.getDouble(3).toFloat(),
                    a.getDouble(4).toFloat(), a.getDouble(5).toFloat()))
        }
        PdfOcrSnapshot(text, expectedHash, words).takeIf(::valid)
    }.getOrNull()
}
