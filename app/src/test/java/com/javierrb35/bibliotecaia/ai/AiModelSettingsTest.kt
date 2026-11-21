package com.javierrb35.bibliotecaia.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiModelSettingsTest {
    @Test fun ScripturaModelIdentifiersAreAcceptedWithoutArbitraryProviderNames() {
        assertTrue(AiModelSettings.valid("gemini-3.6-flash"))
        assertTrue(AiModelSettings.valid("gemini-3.1-flash-lite"))
        assertTrue(AiModelSettings.valid("gemini-3.8-flash"))
        assertFalse(AiModelSettings.valid("gemini/una-ruta"))
        assertFalse(AiModelSettings.valid("../../.env"))
        assertFalse(AiModelSettings.valid(""))
        assertFalse(AiModelSettings.valid("tiene espacios"))
        assertFalse(AiModelSettings.valid("x".repeat(81)))
    }
}
