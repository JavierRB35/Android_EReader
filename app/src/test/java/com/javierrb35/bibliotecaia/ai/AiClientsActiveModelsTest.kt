package com.javierrb35.bibliotecaia.ai

import org.junit.Assert.*
import org.junit.Test

/** Vendor-published model IDs; offline tests do not pretend to call an API. */
class AiClientsActiveModelsTest {
    @Test fun geminiModelNoLongerUsesShutdown20Flash() {
        assertEquals("gemini-3.6-flash", AiClients.GEMINI_MODEL)
        val request = AiClients.geminiBody("Explica esta frase",
            listOf("Fragmento citado, sin spoilers"), emptyList())
        val contents = request.getJSONArray("contents")
        val user = contents.getJSONObject(contents.length() - 1)
            .getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(user.contains("Explica esta frase"))
        assertTrue(user.contains("Fragmento citado"))
        assertFalse(user.contains("Otra parte de la obra"))
    }

    @Test fun groqModelMatchesTheOfficialLlamaInstantMigrationTarget() {
        assertEquals("openai/gpt-oss-20b", AiClients.GROQ_MODEL)
        val request = AiClients.groqBody("Interpreta",
            listOf("Palabras seleccionadas"), emptyList())
        assertEquals(AiClients.GROQ_MODEL, request.getString("model"))
        val messages = request.getJSONArray("messages")
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(messages.length() - 1).getString("role"))
        assertTrue(messages.getJSONObject(messages.length() - 1)
            .getString("content").contains("Palabras seleccionadas"))
    }

    @Test fun distinguishesMissingProviderModelKeyRateLimitAndServerErrors() {
        assertEquals(AiClients.AiErrorKind.MODEL,
            AiClients.classifyHttp(404, "{}").kind)
        assertEquals(AiClients.AiErrorKind.INVALID_KEY,
            AiClients.classifyHttp(401, "{}").kind)
        assertEquals(AiClients.AiErrorKind.QUOTA,
            AiClients.classifyHttp(429, "{}").kind)
        assertEquals(AiClients.AiErrorKind.SERVER,
            AiClients.classifyHttp(503, "{}").kind)
    }
}
