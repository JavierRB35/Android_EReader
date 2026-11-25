package com.javierrb35.bibliotecaia.ai

import org.junit.Assert.*
import org.junit.Test

class DefinitionAiTest {
    @Test fun standaloneDefinitionSendsOnlyPhraseAndIndependentInstructionToBothProviders() {
        val question = DefinitionAi.question("  piedra   angular ")
        assertEquals("Término o expresión: piedra angular", question)
        val gemini = AiClients.geminiBody(question, emptyList(), emptyList(), DefinitionAi.INSTRUCTION)
        val groq = AiClients.groqBody(question, emptyList(), emptyList(), DefinitionAi.INSTRUCTION)
        assertEquals(question, gemini.getJSONArray("contents").getJSONObject(0)
            .getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(question, groq.getJSONArray("messages").getJSONObject(1).getString("content"))
        assertEquals(DefinitionAi.INSTRUCTION, gemini.getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(DefinitionAi.INSTRUCTION, groq.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertFalse(gemini.toString().contains("FRAGMENTOS DEL LIBRO"))
        assertFalse(groq.toString().contains("FRAGMENTOS DEL LIBRO"))
    }

    @Test fun normalReadingRequestsStillUseOriginalGrounding() {
        val body = AiClients.geminiBody("Pregunta", listOf("Texto del libro"), emptyList())
        assertTrue(body.toString().contains("FRAGMENTOS DEL LIBRO"))
        assertEquals(AiClients.SYSTEM_INSTRUCTION, body.getJSONObject("systemInstruction")
            .getJSONArray("parts").getJSONObject(0).getString("text"))
    }
}
