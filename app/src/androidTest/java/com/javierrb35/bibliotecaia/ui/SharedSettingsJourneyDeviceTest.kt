package com.javierrb35.bibliotecaia.ui

import android.content.Context
import android.content.Intent
import android.app.Activity
import android.app.Instrumentation
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
            onView(withText("Configurar biblioteca")).perform(scrollTo(), click())
            onView(withText("Guardar en Drive")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Otra carpeta")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Continuar más tarde")).inRoot(isDialog()).perform(click())
            onView(withText("Recuperar una copia")).perform(scrollTo()).check(matches(isDisplayed()))
        } finally {
            scenario.close()
            app.getSharedPreferences("storage_setup_wizard", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
    @Test fun cloudFirstUsesCreateDocumentAndCancelReturnsToChoice() =
        verifyPicker("Guardar en Drive", Intent.ACTION_CREATE_DOCUMENT, "application/zip")

    @Test fun folderIsSeparateAndCancelReturnsToChoice() =
        verifyPicker("Otra carpeta", Intent.ACTION_OPEN_DOCUMENT_TREE, null)

    private fun verifyPicker(button: String, expected: String, mime: String?) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val captured = AtomicReference<Intent>()
        // Intercept only the system picker, without a real Drive account.
        // Observe the actual intent launched by the wizard, not its button label.
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action !in setOf(Intent.ACTION_CREATE_DOCUMENT, Intent.ACTION_OPEN_DOCUMENT_TREE))
                    return null
                captured.set(Intent(intent))
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        app.getSharedPreferences("storage_setup_wizard", Context.MODE_PRIVATE).edit().clear().commit()
        val scenario = ActivityScenario.launch<SettingsActivity>(Intent(app, SettingsActivity::class.java))
        instrumentation.addMonitor(monitor)
        try {
            onView(withText("Configurar biblioteca")).perform(scrollTo(), click())
            onView(withText(button)).inRoot(isDialog()).perform(click())
            // Espresso waits for the lifecycle coroutine and cancelled result.
            onView(withText("Guardar en Drive")).inRoot(isDialog()).check(matches(isDisplayed()))
            val intent = captured.get()
            assertNotNull("The wizard must launch the requested picker", intent)
            assertEquals(expected, intent.action)
            assertEquals(mime, intent.type)
            onView(withText("Otra carpeta")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Continuar más tarde")).inRoot(isDialog()).perform(click())
        } finally {
            instrumentation.removeMonitor(monitor)
            scenario.close()
            app.getSharedPreferences("storage_setup_wizard", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
