package com.javierrb35.bibliotecaia.reader.epub

/** Navigation follows EPUB spine resources, independent of viewport scroll position. */
internal object EpubChapterNavigation {
    fun chapterIndex(readingOrderUrls: List<String>, currentHref: String?): Int? {
        val href = currentHref?.substringBefore('#') ?: return null
        if (href.isBlank()) return null
        return readingOrderUrls.indexOfFirst {
            it.substringBefore('#') == href
        }.takeIf { it >= 0 }
    }

    fun adjacentIndex(
        readingOrderUrls: List<String>, currentHref: String?, direction: Int
    ): Int? {
        if (direction != -1 && direction != 1) return null
        val current = chapterIndex(readingOrderUrls, currentHref) ?: return null
        return (current + direction).takeIf { it in readingOrderUrls.indices }
    }
}
