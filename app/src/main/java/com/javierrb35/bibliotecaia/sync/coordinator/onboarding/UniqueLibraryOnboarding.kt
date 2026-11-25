package com.javierrb35.bibliotecaia.sync.coordinator.onboarding

import java.util.UUID

/** A create/join attempt is mutually exclusive and must survive process death. */
internal enum class OnboardingMode { CREATE, JOIN }

internal sealed interface OnboardingState {
    object Choose : OnboardingState
    object Unavailable : OnboardingState
    data class Pending(val mode: OnboardingMode) : OnboardingState
    data class Linked(val libraryId: UUID) : OnboardingState
}

/** Transfer both values from a member of the existing library. No new library ID on join. */
internal class PairingInput private constructor(val libraryId: UUID, private val secret: String) {
    fun credential(): String = secret // hand directly to encrypted persistence/transport
    override fun toString(): String = "PairingInput(redacted)"

    companion object {
        private val codePattern = Regex("[a-f0-9]{64}")
        fun parse(libraryId: String, code: String): PairingInput? {
            val id = libraryId.trim()
            val parsed = try { UUID.fromString(id) } catch (_: IllegalArgumentException) { return null }
            if (parsed.toString() != id || !codePattern.matches(code.trim())) return null
            return PairingInput(parsed, code.trim())
        }
    }
}

internal sealed interface OnboardingRequest {
    object Create : OnboardingRequest
    class Join(val pairing: PairingInput) : OnboardingRequest {
        override fun toString(): String = "Join(redacted)"
    }
}

/**
 * The durable adapter owns identity, credentials, bootstrap authorization and all network calls.
 * begin must atomically persist the selected mode and stable identity BEFORE sending any request.
 * It must reject a second begin if already pending or linked (including another caller).
 * For join, it must use the supplied libraryId, not generate one; no legacy ZIP/JSON is created.
 * resume retries the exact pending request, not a new identity or mode.
 * read returns Linked only after authenticated server confirmation is durably committed.
 * On corrupt/unreadable credentials return Unavailable, never Choose.
 * Sensitive pairing and bootstrap material must never enter UI state, logs or backup.
 */
internal interface OnboardingPort {
    fun read(): OnboardingState
    fun begin(request: OnboardingRequest)
    fun resume()
}

internal enum class OnboardingFeedback { NONE, INVALID_PAIRING, BLOCKED, CHECK_CONNECTION }
internal data class OnboardingResult(val state: OnboardingState, val feedback: OnboardingFeedback)

/**
 * Presentation flow for the new first screen. Blocking calls must be dispatched off the UI thread.
 * No network/storage implementation here; keep inactive until the adapter is ready.
 */
internal class UniqueLibraryOnboarding(private val port: OnboardingPort) {
    fun state(): OnboardingState = try {
        port.read()
    } catch (e: java.util.concurrent.CancellationException) {
        throw e
    } catch (_: Exception) {
        OnboardingState.Unavailable
    }

    fun create(): OnboardingResult = start(OnboardingRequest.Create)

    fun link(libraryId: String, code: String): OnboardingResult {
        val current = state()
        if (current != OnboardingState.Choose) return blocked(current)
        val pairing = PairingInput.parse(libraryId, code)
            ?: return OnboardingResult(current, OnboardingFeedback.INVALID_PAIRING)
        return start(OnboardingRequest.Join(pairing))
    }

    fun retry(): OnboardingResult {
        val current = state()
        if (current !is OnboardingState.Pending) return blocked(current)
        return perform { port.resume() }
    }

    private fun start(request: OnboardingRequest): OnboardingResult {
        val current = state()
        if (current != OnboardingState.Choose) return blocked(current)
        return perform { port.begin(request) }
    }

    private fun perform(action: () -> Unit): OnboardingResult {
        var failed = false
        try { action() }
        catch (e: java.util.concurrent.CancellationException) { throw e }
        catch (_: Exception) { failed = true }
        val current = state()
        // A lost response may still have been committed; trust durable state, not a UI guess.
        return OnboardingResult(current,
            if (current is OnboardingState.Linked) OnboardingFeedback.NONE
            else if (failed || current == OnboardingState.Unavailable || current == OnboardingState.Choose)
                OnboardingFeedback.CHECK_CONNECTION
            else OnboardingFeedback.NONE)
    }

    private fun blocked(current: OnboardingState) = OnboardingResult(current,
        if (current == OnboardingState.Unavailable) OnboardingFeedback.CHECK_CONNECTION
        else OnboardingFeedback.BLOCKED)
}
