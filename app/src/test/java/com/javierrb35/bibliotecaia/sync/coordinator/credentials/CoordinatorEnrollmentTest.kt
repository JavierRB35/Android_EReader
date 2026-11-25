package com.javierrb35.bibliotecaia.sync.coordinator.credentials

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CoordinatorEnrollmentTest {
    private class MemoryVault : CredentialVault {
        var bytes: ByteArray? = null
        var writes = 0
        var unavailable = false
        override fun read(): ByteArray? {
            if (unavailable) error("private details")
            return bytes?.copyOf()
        }
        override fun write(value: ByteArray) {
            if (unavailable) error("private details")
            writes++
            bytes = value.copyOf()
        }
    }
    private val code = "c".repeat(64)

    @Test fun createPersistsStableIdentityBeforeRetryAndClearsCodeAfterConfirmation() {
        val v = MemoryVault()
        val first = CoordinatorEnrollment(v).beginCreate(code)
        assertEquals(1, v.writes)
        val retry = CoordinatorEnrollment(v).beginCreate(code)
        assertEquals(first.libraryId, retry.libraryId)
        assertEquals(first.deviceId, retry.deviceId)
        assertEquals(first.deviceCredential(), retry.deviceCredential())
        assertEquals(1, v.writes)
        assertFalse(first.toString().contains(code))
        assertFalse(first.toString().contains(first.deviceCredential()))
        val active = CoordinatorEnrollment(v).confirmMembership(first.libraryId, first.deviceId)
        assertEquals(EnrollmentPhase.ACTIVE, active.phase)
        assertNull(active.enrollmentCode())
        assertEquals(first.deviceCredential(), active.deviceCredential())
        assertEquals(2, v.writes)
        assertFalse(String(v.bytes!!).contains(code))
        assertThrows(CredentialStoreFailure::class.java) { CoordinatorEnrollment(v).beginCreate(code) }
    }

    @Test fun joiningUsesExistingLibraryAndNeverCreatesAnother() {
        val v = MemoryVault()
        val library = UUID.randomUUID()
        val first = CoordinatorEnrollment(v).beginJoin(library, code)
        val retry = CoordinatorEnrollment(v).beginJoin(library, code)
        assertEquals(library, retry.libraryId)
        assertEquals(first.deviceId, retry.deviceId)
        assertEquals(first.deviceCredential(), retry.deviceCredential())
        assertEquals(EnrollmentPhase.JOIN_PENDING, retry.phase)
        assertThrows(CredentialStoreFailure::class.java) { CoordinatorEnrollment(v).beginCreate(code) }
        assertThrows(CredentialStoreFailure::class.java) { CoordinatorEnrollment(v).beginJoin(UUID.randomUUID(), code) }
        assertThrows(CredentialStoreFailure::class.java) { CoordinatorEnrollment(v).confirmMembership(library, UUID.randomUUID()) }
        assertEquals(1, v.writes)
    }

    @Test fun unreadableOrCorruptIdentityNeverSilentlyReplaced() {
        val v = MemoryVault()
        v.bytes = "invalid".toByteArray()
        val err = assertThrows(CredentialStoreFailure::class.java) { CoordinatorEnrollment(v).beginCreate(code) }
        assertNull(err.cause)
        assertEquals(0, v.writes)
        v.unavailable = true
        assertThrows(CredentialStoreFailure::class.java) { CoordinatorEnrollment(v).beginJoin(UUID.randomUUID(), code) }
        assertEquals(0, v.writes)
    }

    @Test fun failedFirstPersistenceNeverReturnsNetworkIdentity() {
        val v = object : CredentialVault {
            override fun read(): ByteArray? = null
            override fun write(value: ByteArray) { error("disk full") }
        }
        assertThrows(CredentialStoreFailure::class.java) { CoordinatorEnrollment(v).beginCreate(code) }
    }
}
