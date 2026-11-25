package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.core.app.ActivityScenario
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
        app.getSharedPreferences("storage_setup_wizard", Context.MODE_PRIVATE).edit().clear().commit()
        val scenario = ActivityScenario.launch<SettingsActivity>(Intent(app, SettingsActivity::class.java))
        try {
            onView(withText("Configurar copia y sincronización")).perform(scrollTo(), click())
            onView(withText("Asistente de copia y sincronización")).check(matches(isDisplayed()))
            onView(withText("Elegir carpeta")).check(matches(isDisplayed()))
            onView(withText("Usar selector de Drive")).check(matches(isDisplayed()))
            onView(withText("Más tarde")).perform(click())
            onView(withText("Sincronización rápida de posiciones")).perform(scrollTo()).check(matches(isDisplayed()))
        } finally {
            scenario.close()
            app.getSharedPreferences("cloud_setup_wizard", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
