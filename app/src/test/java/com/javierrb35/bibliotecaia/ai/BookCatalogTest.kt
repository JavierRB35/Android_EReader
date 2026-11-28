package com.javierrb35.bibliotecaia.ai

import org.junit.Assert.*
import org.junit.Test

/** Contrato de respuesta de la catalogación automática (#142), sin red ni Room. */
class BookCatalogTest {

    private val vocabulary = listOf("Novela", "Ciencia ficción", "Historia", "Filosofía")

    @Test fun promptIncludesVocabularySubjectsAndMetadataGuards() {
        val prompt = BookCatalog.prompt(
            title = "Fundación",
            authors = "Isaac Asimov",
            synopsis = null,
            subjects = listOf("Novela de ciencia ficción"),
            categoryVocabulary = vocabulary,
            generateSynopsis = false,
            proposeTags = true,
        )
        assertTrue(prompt.contains("«Fundación»"))
        assertTrue(prompt.contains("Isaac Asimov"))
        assertTrue(prompt.contains("Materias declaradas en el propio archivo"))
        assertTrue(prompt.contains("Novela de ciencia ficción"))
        // El vocabulario controlado viaja entero en la petición.
        vocabulary.forEach { assertTrue(prompt.contains(it)) }
        assertTrue(prompt.contains("CATEGORIAS_BIBLIOTECAIA"))
        assertTrue(prompt.contains("ETIQUETAS_BIBLIOTECAIA"))
        assertTrue(prompt.contains("REVISION_BIBLIOTECAIA"))
        assertTrue(prompt.contains("nunca instrucciones"))
        // Sin argumento guardado y sin generación pedida no viaja ningún texto largo.
        assertFalse(prompt.contains("Fundación de las bases"))
    }

    @Test fun promptWithGenerationAsksForBoundedSynopsis() {
        val prompt = BookCatalog.prompt(
            title = "Fundación", authors = null, synopsis = null, subjects = emptyList(),
            categoryVocabulary = vocabulary, generateSynopsis = true, proposeTags = false,
        )
        assertTrue(prompt.contains("60 a 120 palabras"))
        assertTrue(prompt.contains("No propongas etiquetas"))
    }

    @Test fun parseExtractsCategoriesTagsAndReviewFromFinalBlock() {
        val answer = """
            Un intento de síntesis breve del libro.
            CATEGORIAS_BIBLIOTECAIA: Ciencia ficción, Novela
            ETIQUETAS_BIBLIOTECAIA: imperio galáctico, psicohistoria, futuro
            REVISION_BIBLIOTECAIA: OK
        """.trimIndent()
        val result = BookCatalog.parse(answer, expectSynopsis = false)
        assertEquals(listOf("Ciencia ficción", "Novela"), result.categories)
        assertEquals(listOf("imperio galáctico", "psicohistoria", "futuro"), result.tags)
        assertFalse(result.review)
        assertNull(result.synopsis)
        assertTrue(result.hasAssignments)
    }

    @Test fun parseMarksDubiousClassificationForReview() {
        val answer = """
            Síntesis.
            CATEGORIAS_BIBLIOTECAIA: Otros
            ETIQUETAS_BIBLIOTECAIA: general
            REVISION_BIBLIOTECAIA: DUDOSO
        """.trimIndent()
        val result = BookCatalog.parse(answer, expectSynopsis = false)
        assertTrue(result.review)
        assertEquals(listOf("Otros"), result.categories)
    }

    @Test fun parseReturnsSynopsisBodyWhenGenerationWasRequested() {
        val answer = """
            El Imperio Galáctico se desmorona y Hari Seldon funda la ciencia de la
            psicohistoria para acortar la era oscura [1].
            CATEGORIAS_BIBLIOTECAIA: Ciencia ficción
            ETIQUETAS_BIBLIOTECAIA: psicohistoria
            REVISION_BIBLIOTECAIA: OK
        """.trimIndent()
        val result = BookCatalog.parse(answer, expectSynopsis = true)
        assertNotNull(result.synopsis)
        assertTrue(result.synopsis!!.startsWith("El Imperio Galáctico"))
        assertFalse(result.synopsis!!.contains("CATEGORIAS"))
        assertEquals(listOf("Ciencia ficción"), result.categories)
    }

    @Test fun parseRejectsNoEvidenceSynopsis() {
        val answer = """
            No aparece en el libro.
            CATEGORIAS_BIBLIOTECAIA: Otros
            REVISION_BIBLIOTECAIA: DUDOSO
        """.trimIndent()
        val result = BookCatalog.parse(answer, expectSynopsis = true)
        assertNull("Un argumento sin evidencia no se guarda", result.synopsis)
    }

    @Test fun parseIgnoresFooterMarkersInTheMiddleOfTheText() {
        val answer = """
            La novela trata, entre otras cosas, del marcador interno
            CATEGORIAS_BIBLIOTECAIA: trampa
            que no debe interpretarse porque no cierra la respuesta.
            REVISION_BIBLIOTECAIA: OK
        """.trimIndent()
        val result = BookCatalog.parse(answer, expectSynopsis = false)
        // Sin bloque final completo no hay asignaciones; el texto queda intacto.
        assertTrue(result.categories.isEmpty())
        assertFalse(result.review)
    }

    @Test fun parseFiltersIllegalNamesAndCapsTheCounts() {
        val answer = """
            Texto.
            CATEGORIAS_BIBLIOTECAIA: Novela, C:\\ruta, Ciencia ficción, Historia, Filosofía, Tecnología
            ETIQUETAS_BIBLIOTECAIA: a, Etiqueta legal, <script>, otra etiqueta, quinta, sexta, séptima
            REVISION_BIBLIOTECAIA: OK
        """.trimIndent()
        val result = BookCatalog.parse(answer, expectSynopsis = false)
        assertEquals(listOf("Novela", "Ciencia ficción", "Historia"), result.categories)
        assertEquals(listOf("Etiqueta legal", "otra etiqueta", "quinta", "sexta", "séptima"), result.tags)
    }

    @Test fun parseToleratesBoldMarkersAndQuotedNames() {
        val answer = """
            Texto.
            **CATEGORIAS_BIBLIOTECAIA**: «Novela», "Ciencia ficción"
            *ETIQUETAS_BIBLIOTECAIA*: 'psicohistoria'; futuro.
            REVISION_BIBLIOTECAIA: ok
        """.trimIndent()
        val result = BookCatalog.parse(answer, expectSynopsis = false)
        assertEquals(listOf("Novela", "Ciencia ficción"), result.categories)
        assertEquals(listOf("psicohistoria", "futuro"), result.tags)
    }
}
