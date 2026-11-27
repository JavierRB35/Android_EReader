package com.javierrb35.bibliotecaia.ai

import org.junit.Assert.*
import org.junit.Test

class BookOverviewTest {
    @Test fun blankBookConsultationBuildsUsefulGroundedSynopsisPrompt() {
        val query = BookOverview.prompt("Don Quijote de la Mancha", "Miguel de Cervantes",
            noSpoilers = true, proposeTags = true)
        assertTrue(query.contains("sinopsis o argumento"))
        assertTrue(query.contains("Don Quijote"))
        assertTrue(query.contains("Evita spoilers"))
        assertTrue(query.contains("ETIQUETAS_BIBLIOTECAIA:"))
        assertFalse(BookOverview.prompt("Libro", null, false, false)
            .contains("Propón entre 3 y 5 etiquetas"))
    }

    @Test fun footerIsRemovedBeforeSavingAndTaggingAndRepeatedOrInvalidLabelsAreIgnored() {
        val raw = "Premisa y desarrollo [1].\n\nETIQUETAS_BIBLIOTECAIA: Aventuras, Humor, aventuras, <script>, Historia medieval"
        val parsed = BookOverview.parse(raw, true)
        assertEquals("Premisa y desarrollo [1].", parsed.synopsis)
        assertEquals(listOf("Aventuras", "Humor", "Historia medieval"), parsed.tags)
        val noTags = BookOverview.parse(raw, false)
        assertEquals(raw, noTags.synopsis)
        assertTrue(noTags.tags.isEmpty())
    }

    @Test fun labelsAreNotAcceptedFromBookTextOrAnUnfinishedResponse() {
        val misleading = "ETIQUETAS_BIBLIOTECAIA: Inventado, Falso\nTexto posterior de la novela"
        val parsed = BookOverview.parse(misleading, true)
        assertTrue(parsed.tags.isEmpty())
        assertEquals(misleading, parsed.synopsis)
        assertTrue(BookOverview.parse("No hay evidencia", true).tags.isEmpty())
    }
}
