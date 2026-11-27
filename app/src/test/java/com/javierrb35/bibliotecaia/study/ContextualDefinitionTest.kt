package com.javierrb35.bibliotecaia.study

import com.javierrb35.bibliotecaia.ai.AiClients
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextualDefinitionTest {
    @Test fun repeatedWordUsesOnlyTheSelectedOccurrence() {
        val text = "Se sentó en un banco del parque. " + "x".repeat(1200) + " Depositó dinero en el banco."
        val start = text.lastIndexOf("banco")
        val passage = ContextualDefinition.fromSelection(text,start,start+5)
        assertEquals("banco",passage.selected)
        assertTrue(passage.before.endsWith("Depositó dinero en el "))
        assertFalse(passage.before.contains("parque"))
        assertEquals(".",passage.after)
        assertTrue(passage.hasContext)
        assertTrue(passage.before.length<=480)
        val payload=JSONObject(ContextualDefinition.fragments("banco",passage).single())
        assertEquals("banco",payload.getString("seleccion_exacta"))
        assertTrue(payload.getBoolean("hay_contexto"))
    }

    @Test fun ambiguousFallbackDoesNotInventContext() {
        val text="Banco de madera. Banco financiero."
        val passage=ContextualDefinition.fromUniqueOccurrence(text,"Banco")
        assertFalse(passage.hasContext)
        assertTrue(ContextualDefinition.fromUniqueOccurrence(text,"financiero").hasContext)
    }

    @Test fun lexicalInstructionUsedOnlyForLexicalRequest() {
        val instruction=ContextualDefinition.INSTRUCTION
        val fragments=ContextualDefinition.fragments("banco",
            ContextualDefinition.fromParts("Depositó dinero en el ","banco","."))
        val gemini=AiClients.geminiBody("Define",fragments,emptyList(),instruction)
        assertEquals(instruction,gemini.getJSONObject("systemInstruction").getJSONArray("parts")
            .getJSONObject(0).getString("text"))
        val groq=AiClients.groqBody("Define",fragments,emptyList(),instruction)
        assertEquals(instruction,groq.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals(AiClients.SYSTEM_INSTRUCTION,AiClients.groqBody("Otra",emptyList(),emptyList())
            .getJSONArray("messages").getJSONObject(0).getString("content"))
    }
}
