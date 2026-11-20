package com.javierrb35.bibliotecaia.reader.pdf

import com.javierrb35.bibliotecaia.study.SelectionAnchors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent page + UTF-16 offset + quote anchor. OCR bitmap-relative boxes
 * also live in the locator, so highlights survive SAF backup/relink and can
 * be painted before OCR is recalculated on a replacement device.
 */
object PdfOcrAnchors {
    private const val VERSION = 1
    private const val MAX_BOXES = 256

    /** Original locator format used by pre-overlay OCR notes. */
    fun locator(page: Int, offset: Int): String {
        require(page >= 0 && offset >= 0)
        return JSONObject().put("format", "PDF").put("page", page)
            .put("source", "OCR").put("textVersion", VERSION)
            .put("offset", offset).toString()
    }

    /** Returns null rather than saving an unpaintable, ambiguous highlight. */
    fun anchoredLocator(
        page: Int, offset: Int, quote: String, snapshot: PdfOcrSnapshot
    ): String? {
        if (!SelectionAnchors.exactMatch(snapshot.text, offset, quote)) return null
        val boxes = PdfOcrGeometry.boxesForRange(snapshot, offset, offset + quote.length)
        if (boxes.isEmpty() || boxes.size > MAX_BOXES) return null
        val array = JSONArray()
        boxes.forEach {
            array.put(JSONArray().put(it.left.toDouble()).put(it.top.toDouble())
                .put(it.right.toDouble()).put(it.bottom.toDouble()))
        }
        return JSONObject(locator(page, offset))
            .put("contentHash", snapshot.contentHash)
            .put("boxes", array).toString()
    }

    fun range(locator: String?, page: Int, text: String, quote: String): IntRange? {
        if (locator.isNullOrBlank() || quote.isBlank() || page < 0) return null
        val saved = runCatching { JSONObject(locator) }.getOrNull() ?: return null
        if (!matchesPage(saved, page)) return null
        val at = saved.optInt("offset", -1)
        return if (SelectionAnchors.exactMatch(text, at, quote))
            at until (at + quote.length)
        else null
    }

    /**
     * Exact edition hash + OCR quote and page are mandatory. If OCR has been
     * regenerated and differs, refuse to paint an unrelated identical quote.
     * When the local OCR cache is absent, stored geometry can still be shown.
     */
    fun overlayBoxes(
        locator: String?, page: Int, quote: String, contentHash: String,
        current: PdfOcrSnapshot? = null
    ): List<PdfOcrRect> {
        if (locator == null || quote.isBlank() || contentHash.isBlank()) return emptyList()
        val obj = runCatching { JSONObject(locator) }.getOrNull() ?: return emptyList()
        if (!matchesPage(obj, page) || obj.optString("contentHash") != contentHash) return emptyList()
        if (current != null && (current.contentHash != contentHash ||
                range(locator, page, current.text, quote) == null)) return emptyList()
        val raw = obj.optJSONArray("boxes") ?: return emptyList()
        if (raw.length() !in 1..MAX_BOXES) return emptyList()
        return runCatching {
            (0 until raw.length()).map { i ->
                val array = raw.getJSONArray(i)
                require(array.length() == 4)
                PdfOcrRect(array.getDouble(0).toFloat(), array.getDouble(1).toFloat(),
                    array.getDouble(2).toFloat(), array.getDouble(3).toFloat())
                    .also { require(it.valid()) }
            }
        }.getOrDefault(emptyList())
    }

    private fun matchesPage(obj: JSONObject, page: Int) =
        obj.optString("format") == "PDF" &&
        obj.optString("source") == "OCR" &&
        obj.optInt("textVersion", -1) == VERSION &&
        obj.optInt("page", -1) == page
}
