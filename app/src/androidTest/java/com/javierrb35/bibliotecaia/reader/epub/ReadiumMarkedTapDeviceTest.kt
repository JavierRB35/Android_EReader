package com.javierrb35.bibliotecaia.reader.epub

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** Targeted regression for the primary Samsung feedback; device gesture QA remains separate. */
@RunWith(AndroidJUnit4::class)
class ReadiumMarkedTapDeviceTest {
    @Test fun onlyRegisteredSavedMarkCanBeOpened() {
        val ids = setOf("nota-1", "subrayado-2")
        assertEquals("nota-1", ReadiumMarkedTap.verifiedId("\"nota-1\"", ids))
        assertEquals("subrayado-2", ReadiumMarkedTap.verifiedId("\"subrayado-2\"", ids))
        assertNull(ReadiumMarkedTap.verifiedId("\"desconocido\"", ids))
        assertNull(ReadiumMarkedTap.verifiedId("null", ids))
        assertNull(ReadiumMarkedTap.verifiedId("{}", ids))
        assertNull(ReadiumMarkedTap.verifiedId(null, ids))
    }

    @Test fun shortTapUsesReadiumDecorationGeometryNotAmbiguousText() {
        val script = ReadiumMarkedTap.hitTestScript(180f, 430f)
        assertTrue(script.contains("getDecorations('annotations')"))
        assertTrue(script.contains("getBoundingClientRect()"))
        assertTrue(script.contains("devicePixelRatio"))
        assertTrue(script.contains("mark.decoration.id"))
        assertFalse(script.contains("document.body.textContent"))
        assertEquals("null", ReadiumMarkedTap.hitTestScript(Float.NaN, 10f))
    }

    @Test fun verticalScrollingWinsAndPaginatedEdgesAreNarrow() {
        // Samsung-like 400px screen; previous Readium adapter used 30% / 120px.
        val width = 400
        val min = 28f
        assertFalse(ReadiumMarkedTap.pageEdge(5f, width, true, min))
        assertFalse(ReadiumMarkedTap.pageEdge(395f, width, true, min))
        assertFalse(ReadiumMarkedTap.pageEdge(65f, width, false, min))
        assertFalse(ReadiumMarkedTap.pageEdge(335f, width, false, min))
        assertTrue(ReadiumMarkedTap.pageEdge(15f, width, false, min))
        assertTrue(ReadiumMarkedTap.pageEdge(389f, width, false, min))
        assertFalse(ReadiumMarkedTap.pageEdge(Float.NaN, width, false, min))
    }
}
