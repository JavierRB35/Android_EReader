package com.javierrb35.bibliotecaia.sync.coordinator.onboarding

import android.view.View
import android.widget.Button
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class UniqueLibraryOnboardingViewTest {
    @Test fun secondInstallationOffersLinkAndBlocksDuplicateClicks() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val expected = UUID.randomUUID().toString()
            var creates = 0
            var links = 0
            var retries = 0
            val view = UniqueLibraryOnboardingView(instrumentation.targetContext,
                object : UniqueLibraryOnboardingView.Actions {
                    override fun create() { creates++ }
                    override fun link(libraryId: String, pairingCode: String) {
                        assertEquals(expected, libraryId)
                        assertEquals("a".repeat(64), pairingCode)
                        links++
                    }
                    override fun retry() { retries++ }
                    override fun check() {}
                    override fun continueToInventory() {}
                })
            fun button(tag: String) = view.findViewWithTag<Button>(tag)
            view.render(OnboardingResult(OnboardingState.Choose, OnboardingFeedback.NONE))
            assertEquals(View.VISIBLE, button("bia_onboarding_create").visibility)
            button("bia_onboarding_link").performClick()
            val id = view.findViewWithTag<EditText>("bia_onboarding_library")
            val secret = view.findViewWithTag<EditText>("bia_onboarding_code")
            id.setText(expected)
            secret.setText("a".repeat(64))
            assertFalse(secret.isSaveEnabled)
            button("bia_onboarding_confirm_link").performClick()
            assertEquals(1, links)
            assertEquals("", secret.text.toString())
            assertFalse(button("bia_onboarding_create").isEnabled)
            button("bia_onboarding_create").performClick()
            assertEquals(0, creates)
            view.render(OnboardingResult(OnboardingState.Pending(OnboardingMode.JOIN), OnboardingFeedback.CHECK_CONNECTION))
            assertEquals(View.GONE, button("bia_onboarding_create").visibility)
            assertEquals(View.GONE, button("bia_onboarding_link").visibility)
            button("bia_onboarding_retry").performClick()
            assertEquals(1, retries)
        }
    }
}
