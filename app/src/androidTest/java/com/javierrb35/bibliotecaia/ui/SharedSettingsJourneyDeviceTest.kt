package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.content.Intent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedSettingsJourneyDeviceTest {
    @Test fun unconfiguredDeviceShowsSingleSetupAndCloudFirst() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val activity = instrumentation.startActivitySync(Intent(app, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        try {
            onView(withText("Configurar biblioteca")).perform(scrollTo(), click())
            onView(withText("Google Drive u otra nube (recomendado)")).check(matches(isDisplayed()))
            onView(withText("Abrir mi archivo de biblioteca existente")).check(matches(isDisplayed()))
            onView(withText("Carpeta compartida compatible (avanzado)")).check(matches(isDisplayed()))
            pressBack()
            onView(withText("Mostrar sincronización antigua (solo posiciones)")).perform(scrollTo()).check(matches(isDisplayed()))
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }
}
