package com.javierrb35.bibliotecaia.reader.pdf

import com.javierrb35.bibliotecaia.study.SelectionAnchors
import org.json.JSONObject

/**
 * PDF OCR anchors are based on an app-private cached transcription for one
 * page of the imported edition. Never search for the same words elsewhere.
 *
 * Coordinates on the image are NOT inferred here: visual marks are restricted
 * to the accessible OCR text panel, not painted onto the PDF bitmap.
 */
object PdfOcrAnchors {
    private const val VERSION = 1

    fun locator(page: Int, offset: Int): String {
        require(page >= 0 && offset >= 0)
        return JSONObject().put("format", "PDF").put("page", page)
            .put("source", "OCR").put("textVersion", VERSION)
            .put("offset", offset).toString()
    }

    fun range(locator: String?, page: Int, text: String, quote: String): IntRange? {
        if (locator.isNullOrBlank() || quote.isBlank() || page < 0) return null
        val saved = runCatching { JSONObject(locator) }.getOrNull() ?: return null
        if (saved.optString("format") != "PDF" ||
            saved.optString("source") != "OCR" ||
            saved.optInt("textVersion", -1) != VERSION ||
            saved.optInt("page", -1) != page
        ) return null
        val at = saved.optInt("offset", -1)
        return if (SelectionAnchors.exactMatch(text, at, quote))
            at until (at + quote.length)
        else null
    }
}
