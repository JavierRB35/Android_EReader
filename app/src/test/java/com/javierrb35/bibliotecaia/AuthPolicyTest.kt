package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.auth.AuthPolicy
import org.junit.Assert.*
import org.junit.Test

class AuthPolicyTest {
    @Test fun onlyHostedHttpsProjectOriginsAreAccepted() {
        assertTrue(AuthPolicy.validProjectUrl("https://abcdefghijklmnopqrst.supabase.co"))
        assertTrue(AuthPolicy.validProjectUrl("https://abcdefghijklmnopqrst.supabase.co/"))
        for (url in listOf("http://abcdefghijklmnopqrst.supabase.co", "https://abcdefghijklmnopqrst.supabase.co.evil.test", "https://user@abcdefghijklmnopqrst.supabase.co", "https://abcdefghijklmnopqrst.supabase.co/path", "https://abcdefghijklmnopqrst.supabase.co?token=x", "https://abcdefghijklmnopqrst.supabase.co#fragment", "https://abcdefghijklmnopqrst.supabase.co:444", "https://127.0.0.1", "")) {
            assertFalse(url, AuthPolicy.validProjectUrl(url))
        }
    }
    @Test fun nonceHashMatchesSha256Vector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AuthPolicy.nonceHash("abc"))
    }
    @Test fun randomNonceIsUrlSafeAndNotReused() {
        val nonces = (0 until 64).map { AuthPolicy.nonce() }
        assertEquals(64, nonces.toSet().size)
        assertTrue(nonces.all { it.matches(Regex("[A-Za-z0-9_-]{43}")) })
    }
    @Test fun refreshIncludesClockMargin() {
        assertTrue(AuthPolicy.needsRefresh(1060,1000))
        assertTrue(AuthPolicy.needsRefresh(900,1000))
        assertFalse(AuthPolicy.needsRefresh(1061,1000))
    }
    @Test fun webClientIdMustUseGoogleDomain() {
        assertTrue(AuthPolicy.validClientId("123456-example.apps.googleusercontent.com"))
        assertFalse(AuthPolicy.validClientId("123456-example.apps.googleusercontent.com.evil.test"))
        assertFalse(AuthPolicy.validClientId(""))
    }
}
