package com.javierrb35.bibliotecaia.reader.epub

import org.junit.Assert.*
import org.junit.Test

class EpubChapterNavigationTest {
    private val chapters = listOf("text/one.xhtml", "text/two.xhtml", "text/three.xhtml")

    @Test fun scrollingArrowsFollowWholeChaptersNotViewportPages() {
        assertEquals(2, EpubChapterNavigation.adjacentIndex(chapters, "text/two.xhtml#p20", 1))
        assertEquals(0, EpubChapterNavigation.adjacentIndex(chapters, "text/two.xhtml#p20", -1))
    }

    @Test fun firstAndLastChapterDisableOutwardNavigation() {
        assertNull(EpubChapterNavigation.adjacentIndex(chapters, chapters.first(), -1))
        assertNull(EpubChapterNavigation.adjacentIndex(chapters, chapters.last(), 1))
    }

    @Test fun unknownAndMissingLocatorNeverJumpToAnArbitraryChapter() {
        assertNull(EpubChapterNavigation.chapterIndex(chapters, null))
        assertNull(EpubChapterNavigation.adjacentIndex(chapters, "missing.xhtml", 1))
        assertNull(EpubChapterNavigation.adjacentIndex(chapters, "text/two.xhtml", 0))
        assertNull(EpubChapterNavigation.adjacentIndex(emptyList(), "text/two.xhtml", 1))
    }
}
