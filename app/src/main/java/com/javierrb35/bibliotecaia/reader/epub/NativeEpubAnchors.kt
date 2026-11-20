package com.javierrb35.bibliotecaia.reader.epub

import com.javierrb35.bibliotecaia.study.SelectionAnchors
import org.json.JSONObject

/**
 * TextView UTF-16 offsets are exact only inside the same normalized XHTML
 * chapter. Never match a repeated quote in another chapter or another offset.
 *
 * The surrounding structure is a valid Readium Locator so the existing
 * repository, annotations and navigation remain backward compatible. Extra
 * "nativeText" metadata is private to our native reader and ignored by Readium.
 */
internal object NativeEpubAnchors {
    private const val KEY = "nativeText"
    private const val VERSION = 1

    data class Point(val href: String, val offset: Int, val length: Int, val version: Int)

    fun locator(href: String, title: String, offset: Int, chapterLength: Int,
                totalProgress: Double, quote: String = ""): String {
        require(offset in 0..chapterLength && quote.length <= chapterLength - offset)
        val normalizedProgress = if (chapterLength == 0) 0.0
            else offset.toDouble() / chapterLength
        val locations = JSONObject()
            .put("progression", normalizedProgress)
            .put("totalProgression", totalProgress.coerceIn(0.0, 1.0))
        val root = JSONObject()
            .put("href", href)
            .put("type", "application/xhtml+xml")
            .put("title", title)
            .put("locations", locations)
            .put(KEY, JSONObject()
                .put("version", VERSION)
                .put("href", href)
                .put("offset", offset)
                .put("length", quote.length))
        if (quote.isNotEmpty())
            root.put("text", JSONObject().put("highlight", quote))
        return root.toString()
    }

    fun point(json: String?): Point? {
        if (json == null) return null
        return try {
            val obj = JSONObject(json)
            val native = obj.optJSONObject(KEY) ?: return null
            val href = obj.optString("href")
            val version = native.optInt("version", -1)
            val offset = native.optInt("offset", -1)
            val length = native.optInt("length", -1)
            val mirroredHref = native.optString("href")
            if (href.isBlank() || mirroredHref != href || offset < 0 ||
                length < 0 || version != VERSION) null
            else Point(href, offset, length, version)
        } catch (_: Exception) {
            null
        }
    }

    fun validatedRange(locator: String?, quote: String, chapterHref: String,
                       plainText: String): IntRange? {
        val point = point(locator) ?: return null
        if (point.href != chapterHref || point.length != quote.length ||
            !SelectionAnchors.exactMatch(plainText, point.offset, quote)) return null
        return point.offset until (point.offset + point.length)
    }
}
