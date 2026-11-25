package com.javierrb35.bibliotecaia.ui

import android.content.Intent
import androidx.test.espresso.Espresso.onView
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
        val activity = instrumentation.startActivitySync(
            Intent(app, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        ) as SettingsActivity
        try {
            onView(withText("Configurar biblioteca")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Recuperar una copia")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("Configurar biblioteca")).perform(scrollTo(), click())
            onView(withText("Elegir ubicación")).check(matches(isDisplayed()))
            onView(withText("Continuar más tarde")).perform(click())
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
