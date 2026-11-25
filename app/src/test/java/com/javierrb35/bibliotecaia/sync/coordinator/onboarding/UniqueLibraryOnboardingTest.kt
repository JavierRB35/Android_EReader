package com.javierrb35.bibliotecaia.sync.coordinator.onboarding

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

class UniqueLibraryOnboardingTest {
    private val existing = UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00")
    private val code = "a".repeat(64)

    private class FakePort : OnboardingPort {
        var current: OnboardingState = OnboardingState.Choose
        var request: OnboardingRequest? = null
        var begins = 0
        var retries = 0
        var failRead = false
        var failBegin = false
        var failAfterConfirm = false
        val generated = UUID.randomUUID()
        override fun read(): OnboardingState {
            if (failRead) throw IOException("private detail")
            return current
        }
        override fun begin(request: OnboardingRequest) {
            check(current == OnboardingState.Choose)
            begins++
            this.request = request
            current = OnboardingState.Pending(
                if (request is OnboardingRequest.Join) OnboardingMode.JOIN else OnboardingMode.CREATE)
            if (failBegin) throw IOException("lost response")
            resume()
        }
        override fun resume() {
            check(current is OnboardingState.Pending)
            retries++
            val chosen = request as? OnboardingRequest.Join
            current = OnboardingState.Linked(chosen?.pairing?.libraryId ?: generated)
            if (failAfterConfirm) throw IOException("response lost")
        }
    }

    @Test fun cleanInstallCanLinkWithoutCreatingLibrary() {
        val port = FakePort()
        val flow = UniqueLibraryOnboarding(port)
        assertEquals(OnboardingState.Choose, flow.state())
        assertEquals(OnboardingState.Linked(existing), flow.link(existing.toString(), code).state)
        assertTrue(port.request is OnboardingRequest.Join)
        assertEquals(1, port.begins)
        assertEquals(OnboardingFeedback.BLOCKED, flow.create().feedback)
        assertEquals(1, port.begins)
    }

    @Test fun newLibraryCannotBeCreatedTwice() {
        val port = FakePort()
        val flow = UniqueLibraryOnboarding(port)
        assertEquals(OnboardingState.Linked(port.generated), flow.create().state)
        assertEquals(OnboardingFeedback.BLOCKED, flow.link(existing.toString(), code).feedback)
        assertEquals(1, port.begins)
    }

    @Test fun invalidPairingNeverStartsNetworkOrPersistence() {
        val port = FakePort()
        val flow = UniqueLibraryOnboarding(port)
        for ((id, secret) in listOf("1-1-1-1-1" to code, existing.toString() to "x".repeat(64),
                existing.toString() to "", "" to code)) {
            assertEquals(OnboardingFeedback.INVALID_PAIRING, flow.link(id, secret).feedback)
        }
        assertEquals(0, port.begins)
        assertEquals(OnboardingState.Choose, flow.state())
    }

    @Test fun restartResumesPendingJoinWithoutSwitchingIdentityOrCreating() {
        val port = FakePort().apply { failBegin = true }
        val initial = UniqueLibraryOnboarding(port).link(existing.toString(), code)
        assertEquals(OnboardingState.Pending(OnboardingMode.JOIN), initial.state)
        assertEquals(OnboardingFeedback.CHECK_CONNECTION, initial.feedback)
        val original = port.request
        val restarted = UniqueLibraryOnboarding(port)
        assertEquals(OnboardingFeedback.BLOCKED, restarted.create().feedback)
        assertEquals(OnboardingFeedback.BLOCKED, restarted.link(UUID.randomUUID().toString(), code).feedback)
        assertEquals(OnboardingState.Linked(existing), restarted.retry().state)
        assertSame(original, port.request)
        assertEquals(1, port.begins)
    }

    @Test fun unreadableStoreFailsClosed() {
        val port = FakePort().apply { failRead = true }
        val flow = UniqueLibraryOnboarding(port)
        assertEquals(OnboardingState.Unavailable, flow.state())
        assertEquals(OnboardingFeedback.CHECK_CONNECTION, flow.create().feedback)
        assertEquals(OnboardingFeedback.CHECK_CONNECTION, flow.link(existing.toString(), code).feedback)
        assertEquals(0, port.begins)
    }

    @Test fun committedMembershipWinsOverLostResponse() {
        val port = FakePort().apply { failAfterConfirm = true }
        val result = UniqueLibraryOnboarding(port).link(existing.toString(), code)
        assertEquals(OnboardingState.Linked(existing), result.state)
        assertEquals(OnboardingFeedback.NONE, result.feedback)
    }

    @Test fun pairingRequestDoesNotRevealSecretInDebugString() {
        val input = PairingInput.parse(existing.toString(), code)!!
        assertFalse(input.toString().contains(code))
        assertFalse(OnboardingRequest.Join(input).toString().contains(code))
    }
}
