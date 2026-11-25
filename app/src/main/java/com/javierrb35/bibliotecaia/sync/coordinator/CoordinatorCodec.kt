package com.javierrb35.bibliotecaia.sync.coordinator

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Wire model derived from bia_coord 001/002; no direct database access.
 * It is deliberately inactive until the gateway and durable store are integrated.
 * Never log requests, raw responses, credentials, codes or private payloads.
 */
internal class ProtocolViolation : IllegalArgumentException("Respuesta o solicitud del coordinador no válida")

private val hashPattern = Regex("[a-f0-9]{64}")
private fun checkProtocol(valid: Boolean) { if (!valid) throw ProtocolViolation() }
private fun validSecret(value: String) = hashPattern.matches(value)
private fun uuid(value: String): UUID {
    val parsed = try { UUID.fromString(value) } catch (_: Exception) { throw ProtocolViolation() }
    checkProtocol(parsed.toString() == value)
    return parsed
}
private fun JSONObject.text(key: String): String = opt(key) as? String ?: throw ProtocolViolation()
private fun JSONObject.number(key: String): Long {
    return when (val n = opt(key)) {
        is Int -> n.toLong()
        is Long -> n
        else -> throw ProtocolViolation()
    }.also { checkProtocol(it >= 0) }
}
private fun JSONObject.flag(key: String): Boolean = opt(key) as? Boolean ?: throw ProtocolViolation()
private fun JSONObject.obj(key: String): JSONObject = opt(key) as? JSONObject ?: throw ProtocolViolation()

private fun parseObject(raw: String, maxBytes: Int): JSONObject {
    checkProtocol(raw.toByteArray(Charsets.UTF_8).size <= maxBytes)
    try {
        val tokener = JSONTokener(raw)
        val value = tokener.nextValue() as? JSONObject ?: throw ProtocolViolation()
        checkProtocol(tokener.nextClean() == '\u0000')
        return value
    } catch (_: Exception) {
        throw ProtocolViolation() // No raw JSON or exception cause may escape.
    }
}

internal class CoordinatorIdentity private constructor(
    val libraryId: UUID, val deviceId: UUID, private val token: String
) {
    fun credential(): String = token // For the authorized transport / secure persistence only.
    override fun toString() = "CoordinatorIdentity(redacted)"

    companion object {
        fun restore(libraryId: UUID, deviceId: UUID, token: String): CoordinatorIdentity {
            checkProtocol(validSecret(token))
            return CoordinatorIdentity(libraryId, deviceId, token)
        }

        /** Persist exactly once before any create/join attempt, including retry. */
        fun generate(libraryId: UUID = UUID.randomUUID()): CoordinatorIdentity {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val token = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
            bytes.fill(0)
            return restore(libraryId, UUID.randomUUID(), token)
        }
    }
}

internal enum class EntityKind(val wire: String, val byHash: Boolean = false) {
    BOOK("book", true), NOTE("note"), MARK("mark"), POSITION("position", true),
    CATEGORY("category"), TAG("tag"), COLLECTION("collection"),
    BOOK_CATEGORY("book_category"), BOOK_TAG("book_tag"), COLLECTION_BOOK("collection_book"),
    SYNOPSIS("synopsis", true), BOOKMARK("bookmark"), ANNOTATION_VERSION("annotation_version");

    companion object {
        fun parse(value: String) = entries.firstOrNull { it.wire == value } ?: throw ProtocolViolation()
    }
}

internal enum class ChangeAction(val wire: String) { PUT("put"), DELETE("delete"), RESTORE("restore") }

internal class EntityKey(val kind: EntityKind, val id: String) {
    init {
        checkProtocol(id.codePointCount(0, id.length) in 1..128 && '\u0000' !in id)
        if (kind.byHash) checkProtocol(hashPattern.matches(id))
    }
    override fun equals(other: Any?) = other is EntityKey && kind == other.kind && id == other.id
    override fun hashCode() = 31 * kind.hashCode() + id.hashCode()
    override fun toString() = "EntityKey(redacted)"
}

internal class PendingChange(
    val operationId: UUID,
    val key: EntityKey,
    val expectedRevision: Long,
    val action: ChangeAction,
    payload: JSONObject
) {
    // Freeze the request so caller mutations cannot silently reuse an operation ID
    // with a different payload. Store this exact logical request durably for retries.
    private val frozen = payload.toString()
    init {
        checkProtocol(expectedRevision >= 0)
        parseObject(frozen, CoordinatorCodec.MAX_PAYLOAD_BYTES)
    }
    fun payload(): JSONObject = parseObject(frozen, CoordinatorCodec.MAX_PAYLOAD_BYTES)
    override fun toString() = "PendingChange(redacted)"
}

internal sealed interface ApplyOutcome {
    data class Applied(val revision: Long, val replayed: Boolean) : ApplyOutcome
    data class Conflict(val revision: Long, val deleted: Boolean) : ApplyOutcome
}

internal class PairingOffer(val libraryId: UUID, private val code: String, val expiresAt: OffsetDateTime) {
    init { checkProtocol(validSecret(code)) }
    fun pairingCode(): String = code
    override fun toString() = "PairingOffer(redacted)"
}

internal class DeltaEntity(val key: EntityKey, val revision: Long, val deleted: Boolean, payload: JSONObject) {
    private val frozen = payload.toString()
    init {
        checkProtocol(revision > 0)
        parseObject(frozen, CoordinatorCodec.MAX_PAYLOAD_BYTES)
    }
    fun payload(): JSONObject = parseObject(frozen, CoordinatorCodec.MAX_PAYLOAD_BYTES)
    override fun toString() = "DeltaEntity(redacted)"
}

internal class DeltaPage(
    val libraryId: UUID, val head: Long, val cursor: Long, entities: List<DeltaEntity>
) {
    val entities: List<DeltaEntity> = java.util.Collections.unmodifiableList(entities.toList())
    override fun toString() = "DeltaPage(redacted)"
}

internal object CoordinatorCodec {
    const val MAX_PAYLOAD_BYTES = 262_144
    const val MAX_RESPONSE_BYTES = 32 * 1024 * 1024
    const val MAX_PAGE_SIZE = 100

    private fun base(identity: CoordinatorIdentity) = JSONObject()
        .put("libraryId", identity.libraryId.toString())
        .put("deviceId", identity.deviceId.toString())

    // Authentication is not serialized into JSON: token belongs in the HTTPS
    // Authorization header. The gateway maps to private SQL function arguments.
    fun create(identity: CoordinatorIdentity) = base(identity)
    fun issuePairing(identity: CoordinatorIdentity) = base(identity)
    fun join(identity: CoordinatorIdentity, code: String): JSONObject {
        checkProtocol(validSecret(code))
        return base(identity).put("code", code)
    }
    fun revoke(identity: CoordinatorIdentity, target: UUID): JSONObject {
        checkProtocol(target != identity.deviceId)
        return base(identity).put("targetDeviceId", target.toString())
    }
    fun apply(identity: CoordinatorIdentity, change: PendingChange): JSONObject =
        base(identity).put("operationId", change.operationId.toString())
            .put("kind", change.key.kind.wire).put("entityId", change.key.id)
            .put("expectedRevision", change.expectedRevision).put("action", change.action.wire)
            .put("payload", change.payload())

    fun pull(identity: CoordinatorIdentity, after: Long, limit: Int): JSONObject {
        checkProtocol(after >= 0 && limit in 1..MAX_PAGE_SIZE)
        return base(identity).put("after", after).put("limit", limit)
    }

    /** For create and join only. Never switch local identity from a response. */
    fun membership(raw: String, identity: CoordinatorIdentity) {
        val o = parseObject(raw, MAX_RESPONSE_BYTES)
        checkProtocol(uuid(o.text("libraryId")) == identity.libraryId)
        checkProtocol(uuid(o.text("deviceId")) == identity.deviceId)
    }

    fun pairing(raw: String, identity: CoordinatorIdentity): PairingOffer {
        val o = parseObject(raw, MAX_RESPONSE_BYTES)
        checkProtocol(uuid(o.text("libraryId")) == identity.libraryId)
        val expiry = try { OffsetDateTime.parse(o.text("expiresAt")) } catch (_: Exception) { throw ProtocolViolation() }
        return PairingOffer(identity.libraryId, o.text("code"), expiry)
    }

    fun applied(raw: String, change: PendingChange): ApplyOutcome {
        val o = parseObject(raw, MAX_RESPONSE_BYTES)
        val rev = o.number("revision")
        return when (o.text("status")) {
            "applied" -> {
                checkProtocol(rev > change.expectedRevision)
                ApplyOutcome.Applied(rev, o.flag("replayed"))
            }
            "conflict" -> ApplyOutcome.Conflict(rev, o.flag("deleted"))
            else -> throw ProtocolViolation()
        }
    }

    /** Validate a latest-entity delta before the caller commits entities+cursor atomically. */
    fun delta(raw: String, identity: CoordinatorIdentity, after: Long, limit: Int): DeltaPage {
        checkProtocol(after >= 0 && limit in 1..MAX_PAGE_SIZE)
        val o = parseObject(raw, MAX_RESPONSE_BYTES)
        checkProtocol(uuid(o.text("libraryId")) == identity.libraryId)
        val head = o.number("head")
        val cursor = o.number("cursor")
        checkProtocol(head >= after && cursor in after..head)
        val array = o.opt("items") as? JSONArray ?: throw ProtocolViolation()
        checkProtocol(array.length() <= limit)
        val keys = mutableSetOf<EntityKey>()
        val entities = mutableListOf<DeltaEntity>()
        var previous = after
        for (i in 0 until array.length()) {
            val item = array.opt(i) as? JSONObject ?: throw ProtocolViolation()
            val key = EntityKey(EntityKind.parse(item.text("kind")), item.text("entity_id"))
            val revision = item.number("revision")
            checkProtocol(revision > previous && revision <= cursor && keys.add(key))
            entities.add(DeltaEntity(key, revision, item.flag("deleted"), item.obj("payload")))
            previous = revision
        }
        // SQL advances to head only when fewer than limit live entities remain.
        // A full page must stop precisely at its last delivered revision.
        checkProtocol(if (entities.size == limit) cursor == previous else cursor == head)
        return DeltaPage(identity.libraryId, head, cursor, entities)
    }
}
