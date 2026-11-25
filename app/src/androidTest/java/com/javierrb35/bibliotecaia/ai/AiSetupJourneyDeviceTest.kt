package com.javierrb35.bibliotecaia.ai

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.javierrb35.bibliotecaia.ui.SettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiSetupJourneyDeviceTest {
    @Test fun settingsOpensIndependentKeysAndDefaultBackup() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = app.getSharedPreferences("bibliotecaia_ai_fallback_consent", Context.MODE_PRIVATE)
        fun clean() {
            AiKeyStore.deleteKey(app, AiClients.GEMINI)
            AiKeyStore.deleteKey(app, AiClients.GROQ)
            prefs.edit().clear().commit()
        }
        clean()
        val scenario = ActivityScenario.launch<SettingsActivity>(Intent(app, SettingsActivity::class.java))
        try {
            onView(withText("Configurar inteligencia artificial")).perform(scrollTo(), click())
            onView(withContentDescription("Clave API de Gemini")).perform(scrollTo(), replaceText("synthetic-gemini"), closeSoftKeyboard())
            onView(withContentDescription("Clave API de Groq")).perform(scrollTo()).check(matches(withText("")))
            onView(withContentDescription("Clave API de Groq")).perform(replaceText("synthetic-groq"), closeSoftKeyboard())
            onView(withText("Guardar clave Gemini")).perform(scrollTo(), click())
            assertEquals("synthetic-gemini", AiKeyStore.getKey(app, AiClients.GEMINI))
            assertNull(AiKeyStore.getKey(app, AiClients.GROQ))
            onView(withContentDescription("Clave API de Gemini")).perform(scrollTo()).check(matches(withText("")))
            onView(withContentDescription("Clave API de Groq")).perform(scrollTo()).check(matches(withText("synthetic-groq")))
            onView(withText("Guardar clave Groq")).perform(scrollTo(), click())
            assertEquals("synthetic-groq", AiKeyStore.getKey(app, AiClients.GROQ))
            assertEquals("synthetic-gemini", AiKeyStore.getKey(app, AiClients.GEMINI))
            onView(withContentDescription("Respaldo automático con Groq")).perform(scrollTo()).check(matches(isChecked()))
            assertTrue(AiFallbackConsent.enabled(app))
            onView(withContentDescription("Respaldo automático con Groq")).perform(click())
            assertFalse(AiFallbackConsent.enabled(app))
            onView(withText("Eliminar clave Groq")).perform(scrollTo(), click())
            onView(withText("Eliminar")).perform(click())
            assertNull(AiKeyStore.getKey(app, AiClients.GROQ))
            assertEquals("synthetic-gemini", AiKeyStore.getKey(app, AiClients.GEMINI))
            pressBack()
            onView(withText("Configurar inteligencia artificial")).perform(scrollTo()).check(matches(isDisplayed()))
        } finally {
            scenario.close()
            clean()
        }
    }

    @Test fun legacyDisabledAndNewExplicitOptOutAreNotOverridden() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = app.getSharedPreferences("bibliotecaia_ai_fallback_consent", Context.MODE_PRIVATE)
        AiKeyStore.deleteKey(app, AiClients.GROQ)
        prefs.edit().clear().commit()
        try {
            assertTrue(AiFallbackConsent.preferred(app))
            assertFalse(AiFallbackConsent.enabled(app))
            assertTrue(AiKeyStore.saveKey(app, AiClients.GROQ, "synthetic-groq"))
            prefs.edit().putBoolean("groq_fallback_authorized", false).commit()
            assertFalse(AiFallbackConsent.enabled(app))
            AiFallbackConsent.setEnabled(app, true)
            assertTrue(AiFallbackConsent.enabled(app))
            AiFallbackConsent.setEnabled(app, false)
            assertTrue(AiKeyStore.saveKey(app, AiClients.GROQ, "replacement-synthetic"))
            assertFalse(AiFallbackConsent.enabled(app))
        } finally {
            AiKeyStore.deleteKey(app, AiClients.GROQ)
            prefs.edit().clear().commit()
        }
    }
}
