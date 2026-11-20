package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.auth.AuthConfiguration
import org.junit.Assert.*
import org.junit.Test

class AuthConfigurationTest {
    private val url = "https://abcdefghijklmnopqrst.supabase.co"
    private val publishable = "sb_publishable_ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    @Test fun emailBackendWorksWithoutGoogleOauthClientId() {
        val config = AuthConfiguration(url, publishable, "")
        assertTrue(config.backendConfigured)
        assertFalse(config.googleConfigured)
        assertFalse(config.configured)
    }

    @Test fun googleSignInNeedsWebClientIdOnTopOfBackend() {
        val config = AuthConfiguration(url, publishable,
            "1234567890-example.apps.googleusercontent.com")
        assertTrue(config.backendConfigured)
        assertTrue(config.googleConfigured)
    }

    @Test fun emailBackendRejectsEmptyAndSecretKeys() {
        for (key in listOf("", "sb_secret_dont_use_0123456789", "service_role")) {
            assertFalse(AuthConfiguration(url, key, "").backendConfigured)
        }
        assertFalse(AuthConfiguration("https://example.org", publishable, "").backendConfigured)
    }
}
