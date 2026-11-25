package com.javierrb35.bibliotecaia.sync.coordinator

import com.javierrb35.bibliotecaia.sync.coordinator.credentials.*
import com.javierrb35.bibliotecaia.sync.coordinator.onboarding.*

/** The approval code is supplied through an authorized provisioning flow, not
 * embedded in the application. No default implementation or endpoint. */
internal fun interface BootstrapApproval { fun authorizedCode(): String }

internal class EnrollmentOnboardingAdapter(
    private val enrollment: CoordinatorEnrollment,
    private val transport: CoordinatorTransport,
    private val bootstrap: BootstrapApproval
) : OnboardingPort {
    private val client = CoordinatorClient(transport)
    companion object { private val enrollmentLock = Any() }

    override fun read(): OnboardingState = try {
        val state = enrollment.current()
        when (state?.phase) {
            null -> OnboardingState.Choose
            EnrollmentPhase.CREATE_PENDING -> OnboardingState.Pending(OnboardingMode.CREATE)
            EnrollmentPhase.JOIN_PENDING -> OnboardingState.Pending(OnboardingMode.JOIN)
            EnrollmentPhase.ACTIVE -> OnboardingState.Linked(state.libraryId)
        }
    } catch (_: CredentialStoreFailure) { OnboardingState.Unavailable }

    override fun begin(request: OnboardingRequest) = synchronized(enrollmentLock) {
        check(enrollment.current() == null) { "Ya hay una identidad o alta pendiente" }
        val record = when (request) {
            OnboardingRequest.Create -> enrollment.beginCreate(bootstrap.authorizedCode())
            is OnboardingRequest.Join -> enrollment.beginJoin(request.pairing.libraryId, request.pairing.credential())
        }
        complete(record)
    }

    override fun resume() = synchronized(enrollmentLock) {
        val record = enrollment.current() ?: throw CredentialStoreFailure()
        check(record.phase != EnrollmentPhase.ACTIVE)
        complete(record)
    }

    private fun complete(record: StoredEnrollment) {
        val id = record.identity()
        val code = record.enrollmentCode() ?: throw CredentialStoreFailure()
        when (record.phase) {
            EnrollmentPhase.CREATE_PENDING -> {
                // Bootstrap approval is separate from a self-generated device token.
                val request = CoordinatorCodec.create(id).put("approvalCode", code)
                CoordinatorCodec.membership(transport.post(CoordinatorOperation.CREATE, id, request), id)
            }
            EnrollmentPhase.JOIN_PENDING -> client.join(id, code)
            EnrollmentPhase.ACTIVE -> error("La biblioteca ya está vinculada")
        }
        enrollment.confirmMembership(id.libraryId, id.deviceId)
    }
}

internal fun StoredEnrollment.identity() =
    CoordinatorIdentity.restore(libraryId, deviceId, deviceCredential())
