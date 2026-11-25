package com.javierrb35.bibliotecaia.sync.coordinator

import com.javierrb35.bibliotecaia.sync.coordinator.credentials.*
import com.javierrb35.bibliotecaia.sync.coordinator.onboarding.*
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class EnrollmentOnboardingAdapterTest {
    private class MemoryVault : CredentialVault {
        var bytes: ByteArray? = null
        override fun read() = bytes?.copyOf()
        override fun write(value: ByteArray) { bytes = value.copyOf() }
    }

    @Test fun pairTwoInstallationsAndRetryLostResponseWithSameIdentity() {
        val a = MemoryVault()
        val b = MemoryVault()
        val approval = "a".repeat(64)
        val pairing = "b".repeat(64)
        var firstJoin: CoordinatorIdentity? = null
        var loseResponse = true
        val server = CoordinatorTransport { operation, id, body ->
            when (operation) {
                CoordinatorOperation.CREATE -> assertEquals(approval, body.getString("approvalCode"))
                CoordinatorOperation.JOIN -> {
                    assertEquals(pairing, body.getString("code"))
                    if (firstJoin == null) firstJoin = id
                    else {
                        assertEquals(firstJoin!!.libraryId, id.libraryId)
                        assertEquals(firstJoin!!.deviceId, id.deviceId)
                        assertEquals(firstJoin!!.credential(), id.credential())
                    }
                    if (loseResponse) { loseResponse = false; throw CoordinatorConnectionException(ConnectionFailure.NETWORK) }
                }
                else -> error("unexpected")
            }
            CoordinatorCodec.create(id).toString()
        }
        val aFlow = UniqueLibraryOnboarding(EnrollmentOnboardingAdapter(CoordinatorEnrollment(a), server, BootstrapApproval { approval }))
        val linkedA = aFlow.create().state as OnboardingState.Linked
        fun bFlow() = UniqueLibraryOnboarding(EnrollmentOnboardingAdapter(CoordinatorEnrollment(b), server,
            BootstrapApproval { error("join must not request bootstrap") }))
        val pendingB = bFlow().link(linkedA.libraryId.toString(), pairing)
        assertEquals(OnboardingState.Pending(OnboardingMode.JOIN), pendingB.state)
        assertEquals(OnboardingFeedback.CHECK_CONNECTION, pendingB.feedback)
        assertEquals(OnboardingState.Linked(linkedA.libraryId), bFlow().retry().state)
        assertNotEquals(CoordinatorEnrollment(a).current()!!.deviceId, CoordinatorEnrollment(b).current()!!.deviceId)
        assertNull(CoordinatorEnrollment(b).current()!!.enrollmentCode())
    }

    @Test fun bootstrapFailureDoesNotGenerateIdentity() {
        val v = MemoryVault()
        val flow = UniqueLibraryOnboarding(EnrollmentOnboardingAdapter(CoordinatorEnrollment(v),
            CoordinatorTransport { _, _, _ -> error("must not reach server") },
            BootstrapApproval { error("no authorization") }))
        assertEquals(OnboardingState.Choose, flow.create().state)
        assertNull(v.bytes)
    }
}
