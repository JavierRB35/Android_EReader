package com.javierrb35.bibliotecaia.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Port of Scriptura AiRouterTest, adapted to per-book immutable queries. */
class AiFallbackRouterTest {
    private fun answer(provider: String) = AiClients.Answer(
        "Respuesta desde " + provider, provider, "modelo")

    private fun error(kind: AiClients.AiErrorKind) = AiClients.AiException(kind, kind.name)

    @Test fun geminiSuccessNeverCallsGroq() {
        val calls = mutableListOf<String>()
        val result = AiFallbackRouter.ask(true, true, { true }) {
            calls += it
            answer(it)
        }
        assertEquals(listOf(AiClients.GEMINI), calls)
        assertEquals(AiClients.GEMINI, result.provider)
    }

    @Test fun quotaTriggersExactlyOneGroqCallWithSameRequest() {
        val calls = mutableListOf<String>()
        val request = listOf("CITA elegida", "[1] contexto", "Pregunta del lector")
        val contexts = mutableListOf<List<String>>()
        val result = AiFallbackRouter.ask(true, true, { true }) { provider ->
            calls += provider
            contexts += request
            if (provider == AiClients.GEMINI) throw error(AiClients.AiErrorKind.QUOTA)
            answer(provider)
        }
        assertEquals(listOf(AiClients.GEMINI, AiClients.GROQ), calls)
        assertEquals(listOf(request, request), contexts)
        assertEquals(AiClients.GROQ, result.provider)
    }

    @Test fun serverAndTimeoutPermitBackupOnlyWithConsent() {
        listOf(AiClients.AiErrorKind.SERVER, AiClients.AiErrorKind.TIMEOUT).forEach { kind ->
            val calls = mutableListOf<String>()
            val result = AiFallbackRouter.ask(true, true, { true }) { provider ->
                calls += provider
                if (provider == AiClients.GEMINI) throw error(kind)
                answer(provider)
            }
            assertEquals(AiClients.GROQ, result.provider)
            assertEquals(2, calls.size)
        }
    }

    @Test fun noConsentOrNoGroqNeverTransmitsSecondRequest() {
        for ((hasGroq, allowed) in listOf(true to false, false to true)) {
            var backupCalls = 0
            try {
                AiFallbackRouter.ask(true, hasGroq, { allowed }) { provider ->
                    if (provider == AiClients.GROQ) backupCalls++
                    throw error(AiClients.AiErrorKind.QUOTA)
                }
                fail("Gemini must report its unavailable quota")
            } catch (_: AiClients.AiException) {
                assertEquals(0, backupCalls)
            }
        }
        assertFalse(AiFallbackRouter.eligible(error(AiClients.AiErrorKind.NETWORK)))
    }

    @Test fun revokedConsentIsCheckedAfterGeminiFailure() {
        var consent = true
        var backups = 0
        try {
            AiFallbackRouter.ask(true, true, { consent }) { provider ->
                if (provider == AiClients.GEMINI) {
                    consent = false // User revokes during the first request.
                    throw error(AiClients.AiErrorKind.SERVER)
                }
                backups++
                answer(provider)
            }
            fail("Must stop after consent revocation")
        } catch (_: AiClients.AiException) {
            assertEquals(0, backups)
        }
    }

    @Test fun nonRecoverableErrorsNeverUseSecondProvider() {
        val noRetry = listOf(
            AiClients.AiErrorKind.INVALID_KEY,
            AiClients.AiErrorKind.PERMISSION,
            AiClients.AiErrorKind.MODEL,
            AiClients.AiErrorKind.PARSE,
            AiClients.AiErrorKind.NETWORK)
        for (kind in noRetry) {
            var calls = 0
            try {
                AiFallbackRouter.ask(true, true, { true }) {
                    calls++
                    throw error(kind)
                }
                fail("must keep original error")
            } catch (e: AiClients.AiException) {
                assertEquals(kind, e.kind)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun noGeminiKeyRetainsDirectConfiguredGroq() {
        val result = AiFallbackRouter.ask(false, true, { false }) {
            assertEquals(AiClients.GROQ, it)
            answer(it)
        }
        assertEquals(AiClients.GROQ, result.provider)
    }

    @Test fun bothFailuresReportedWithoutInfiniteRetries() {
        var calls = 0
        try {
            AiFallbackRouter.ask(true, true, { true }) { provider ->
                calls++
                throw error(if (provider == AiClients.GEMINI)
                    AiClients.AiErrorKind.QUOTA else AiClients.AiErrorKind.SERVER)
            }
            fail("Both failures must be surfaced")
        } catch (e: AiFallbackRouter.BothUnavailableException) {
            assertTrue(e.message!!.contains("Groq"))
            assertEquals(2, calls)
            assertEquals(1, e.suppressed.size)
        }
    }
}
