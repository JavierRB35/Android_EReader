package com.javierrb35.bibliotecaia.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Evidence numbering must be identical for Gemini, Groq and the saved synopsis. */
class AiSourceReferencesTest {
    @Test fun nonConsecutiveBookChaptersUseConsecutiveSourceCitations() {
        val references = AiSourceReferences.prepare(
            "Ensayo", null,
            listOf(
                BookTextSource.Fragment(7, "Capítulo siete", "Primera evidencia."),
                BookTextSource.Fragment(23, "Capítulo veintitrés", "Segunda evidencia.")
            ))
        assertEquals(listOf(1, 2), references.map { it.number })
        val prompt = AiClients.contextBlock(AiSourceReferences.requestContext(references))
        assertTrue(prompt.contains("[1] Capítulo siete"))
        assertTrue(prompt.contains("[2] Capítulo veintitrés"))
        assertFalse(prompt.contains("[7]"))
        assertFalse(prompt.contains("[23]"))
        assertEquals("[1] Capítulo siete · [2] Capítulo veintitrés",
            AiSourceReferences.shortLegend(references))
    }

    @Test fun exactSelectionComesBeforeRetrievedChaptersWithSameNumbering() {
        val references = AiSourceReferences.prepare(
            "Novela", "Cita literal.",
            listOf(BookTextSource.Fragment(12, "Capítulo doce", "Contexto de la novela.")))
        assertEquals(listOf(1, 2), references.map { it.number })
        val prompt = AiClients.contextBlock(AiSourceReferences.requestContext(references))
        assertTrue(prompt.contains("[1] Cita seleccionada · Novela"))
        assertTrue(prompt.contains("Cita literal."))
        assertTrue(prompt.contains("[2] Capítulo doce"))
        assertEquals(1, Regex("""\[1\]""").findAll(prompt).count())
        assertEquals(1, Regex("""\[2\]""").findAll(prompt).count())
    }

    @Test fun synopsisPreservesReferencesForOfflineReading() {
        val references = AiSourceReferences.prepare(
            "Libro", null,
            listOf(BookTextSource.Fragment(2, "Sección dos", "Contenido.")))
        val saved = AiSourceReferences.synopsisWithSources("Argumento del libro.", references)
        assertEquals("Argumento del libro.\n\nFuentes consultadas:\n[1] Sección dos", saved)
        assertEquals("Argumento sin fuentes.",
            AiSourceReferences.synopsisWithSources(" Argumento sin fuentes. ", emptyList()))
    }
}
