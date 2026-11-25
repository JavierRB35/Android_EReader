package com.javierrb35.bibliotecaia.ui

import android.content.Intent
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Guard against a visually invisible first backup or an implicit 'update only' action. */
@RunWith(AndroidJUnit4::class)
class SettingsBackupJourneyDeviceTest {
    @Test fun firstBackupAndDestinationChoiceAreExplicitlyReachable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val scenario = ActivityScenario.launch<SettingsActivity>(Intent(app, SettingsActivity::class.java))
        try {
            onView(withText("Configurar biblioteca")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Recuperar una copia")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Configurar biblioteca")).perform(scrollTo(), click())
            onView(withText("Seleccionar carpeta")).inRoot(isDialog()).check(matches(isDisplayed()))
            onView(withText("Continuar más tarde")).inRoot(isDialog()).perform(click())
        } finally {
            scenario.close()
        }
    }
}
