package com.javierrb35.bibliotecaia.sync.coordinator.credentials

import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

internal enum class EnrollmentPhase { CREATE_PENDING, JOIN_PENDING, ACTIVE }
internal class CredentialStoreFailure : IllegalStateException("No se pudo recuperar o guardar la identidad. No se creará otra automáticamente.")

internal class StoredEnrollment(
    val libraryId: UUID,
    val deviceId: UUID,
    private val token: String,
    val phase: EnrollmentPhase,
    private val code: String?
) {
    init {
        require(isSecret(token))
        require(if (phase == EnrollmentPhase.ACTIVE) code == null else code != null && isSecret(code))
    }
    fun deviceCredential(): String = token
    fun enrollmentCode(): String? = code
    fun confirm(): StoredEnrollment = StoredEnrollment(libraryId, deviceId, token, EnrollmentPhase.ACTIVE, null)
    override fun toString() = "StoredEnrollment(redacted)"
    companion object {
        fun isSecret(value: String) = value.matches(Regex("[a-f0-9]{64}"))
        fun randomSecret(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return try { bytes.joinToString("") { "%02x".format(it.toInt() and 255) } }
            finally { bytes.fill(0) }
        }
    }
}

internal object EnrollmentCodec {
    fun encode(value: StoredEnrollment): ByteArray = JSONObject()
        .put("version", 1)
        .put("libraryId", value.libraryId.toString())
        .put("deviceId", value.deviceId.toString())
        .put("token", value.deviceCredential())
        .put("phase", value.phase.name)
        .put("code", value.enrollmentCode() ?: JSONObject.NULL)
        .toString().toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): StoredEnrollment {
        try {
            require(bytes.size in 1..4096)
            val o = JSONObject(String(bytes, Charsets.UTF_8))
            require(o.opt("version") == 1)
            fun id(key: String): UUID {
                val raw = o.opt(key) as? String ?: error("invalid")
                return UUID.fromString(raw).also { require(it.toString() == raw) }
            }
            val code = o.opt("code").let {
                when (it) { JSONObject.NULL -> null; is String -> it; else -> error("invalid") }
            }
            return StoredEnrollment(id("libraryId"), id("deviceId"), o.opt("token") as String,
                EnrollmentPhase.valueOf(o.opt("phase") as String), code)
        } catch (_: Exception) {
            throw CredentialStoreFailure()
        } finally {
            bytes.fill(0)
        }
    }
}

/** Implementations return null only for positively established first use.
 * Corruption, inaccessible storage or lost key must throw, never return null. */
internal interface CredentialVault {
    fun read(): ByteArray?
    fun write(value: ByteArray)
}

/** Synchronous persistence before network; callers run this off the UI thread.
 * No destructive reset API. Recovery/revocation requires an explicit future flow. */
internal class CoordinatorEnrollment(private val vault: CredentialVault) {
    companion object { private val lock = Any() }
    private fun read(): StoredEnrollment? = try { vault.read()?.let(EnrollmentCodec::decode) }
    catch (_: Exception) { throw CredentialStoreFailure() }

    private fun save(record: StoredEnrollment): StoredEnrollment {
        val bytes = EnrollmentCodec.encode(record)
        try { vault.write(bytes) }
        catch (_: Exception) { throw CredentialStoreFailure() }
        finally { bytes.fill(0) }
        return record
    }

    fun current(): StoredEnrollment? = synchronized(lock) { read() }

    fun beginCreate(approvalCode: String): StoredEnrollment = synchronized(lock) {
        require(StoredEnrollment.isSecret(approvalCode)) { "Código de alta inválido" }
        val old = read()
        if (old != null) {
            if (old.phase == EnrollmentPhase.CREATE_PENDING && old.enrollmentCode() == approvalCode) old
            else throw CredentialStoreFailure()
        } else save(StoredEnrollment(UUID.randomUUID(), UUID.randomUUID(), StoredEnrollment.randomSecret(),
            EnrollmentPhase.CREATE_PENDING, approvalCode))
    }

    fun beginJoin(libraryId: UUID, pairingCode: String): StoredEnrollment = synchronized(lock) {
        require(StoredEnrollment.isSecret(pairingCode)) { "Código de vinculación inválido" }
        val old = read()
        if (old != null) {
            if (old.phase == EnrollmentPhase.JOIN_PENDING && old.libraryId == libraryId &&
                old.enrollmentCode() == pairingCode) old else throw CredentialStoreFailure()
        } else save(StoredEnrollment(libraryId, UUID.randomUUID(), StoredEnrollment.randomSecret(),
            EnrollmentPhase.JOIN_PENDING, pairingCode))
    }

    /** Call only after the authenticated gateway membership response is validated.
     * A mismatched response can never replace this installation's identity. */
    fun confirmMembership(libraryId: UUID, deviceId: UUID): StoredEnrollment = synchronized(lock) {
        val old = read() ?: throw CredentialStoreFailure()
        if (old.libraryId != libraryId || old.deviceId != deviceId) throw CredentialStoreFailure()
        if (old.phase == EnrollmentPhase.ACTIVE) old else save(old.confirm())
    }
}
