package com.javierrb35.bibliotecaia.sync.coordinator

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CoordinatorCodecTest {
    private val library = UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00")
    private val device = UUID.fromString("aabbccdd-eeff-0011-2233-445566778899")
    private val token = "a".repeat(64)
    private val identity = CoordinatorIdentity.restore(library, device, token)

    private fun invalid(block: () -> Unit) {
        try {
            block()
            fail("Malformed coordinator data must be rejected")
        } catch (e: ProtocolViolation) {
            assertFalse(e.message.orEmpty().contains(token))
            assertNull(e.cause)
        }
    }

    private fun change(revision: Long = 0, payload: JSONObject = JSONObject().put("text", "note")) =
        PendingChange(UUID.randomUUID(), EntityKey(EntityKind.NOTE, "n1"), revision, ChangeAction.PUT, payload)

    @Test fun identityAndPairingAreBoundToOneLibrary() {
        val generated = CoordinatorIdentity.generate(library)
        assertEquals(library, generated.libraryId)
        assertEquals(64, generated.credential().length)
        assertNotEquals(token, generated.credential())
        assertFalse(identity.toString().contains(token))
        invalid { CoordinatorIdentity.restore(library, device, "bad") }
        val code = "b".repeat(64)
        val join = CoordinatorCodec.join(identity, code)
        assertEquals(library.toString(), join.getString("libraryId"))
        assertEquals(device.toString(), join.getString("deviceId"))
        assertEquals(code, join.getString("code"))
        assertFalse(join.toString().contains(token))
        assertFalse(CoordinatorCodec.create(identity).has("token"))
        invalid { CoordinatorCodec.join(identity, "short") }
        val reply = CoordinatorCodec.create(identity)
        CoordinatorCodec.membership(reply.toString(), identity)
        invalid { CoordinatorCodec.membership(reply.put("libraryId", UUID.randomUUID().toString()).toString(), identity) }
        val offer = JSONObject().put("libraryId", library.toString()).put("code", code)
            .put("expiresAt", "2026-11-25T15:20:00+00:00")
        assertFalse(CoordinatorCodec.pairing(offer.toString(), identity).toString().contains(code))
    }

    @Test fun stableOperationSurvivesRetryAndPayloadMutation() {
        val payload = JSONObject().put("text", "original")
        val operation = change(2, payload)
        val first = CoordinatorCodec.apply(identity, operation)
        payload.put("text", "changed")
        operation.payload().put("text", "changed again")
        val retry = CoordinatorCodec.apply(identity, operation)
        assertEquals(first.toString(), retry.toString())
        assertEquals("original", retry.getJSONObject("payload").getString("text"))
        assertEquals(operation.operationId.toString(), retry.getString("operationId"))
        assertFalse(retry.toString().contains(token))
        assertEquals(ApplyOutcome.Applied(3, true),
            CoordinatorCodec.applied("""{"status":"applied","revision":3,"replayed":true}""", operation))
        assertEquals(ApplyOutcome.Conflict(4, true),
            CoordinatorCodec.applied("""{"status":"conflict","revision":4,"deleted":true}""", operation))
        invalid { CoordinatorCodec.applied("""{"status":"applied","revision":2,"replayed":false}""", operation) }
        invalid { CoordinatorCodec.applied("""{"status":"applied","revision":"3","replayed":false}""", operation) }
        invalid { CoordinatorCodec.applied("""{"status":"conflict","revision":4,"deleted":"true"}""", operation) }
        invalid { CoordinatorCodec.applied("""{"status":"applied","revision":3,"replayed":false} trailing""", operation) }
        invalid { change(-1) }
        invalid { EntityKey(EntityKind.POSITION, "not-a-hash") }
        invalid { change(payload = JSONObject().put("text", "x".repeat(262144))) }
    }

    private fun entity(id: String, revision: Long, deleted: Boolean = false) = JSONObject()
        .put("kind", "note").put("entity_id", id).put("revision", revision)
        .put("deleted", deleted).put("payload", JSONObject().put("text", "private"))

    private fun page(head: Long, cursor: Long, vararg items: JSONObject) = JSONObject()
        .put("libraryId", library.toString()).put("head", head).put("cursor", cursor)
        .put("items", JSONArray(items.toList()))

    @Test fun deltaSupportsGapsTombstonesAndPagination() {
        val full = CoordinatorCodec.delta(page(9, 5, entity("a", 3), entity("b", 5, true)).toString(), identity, 1, 2)
        assertEquals(5L, full.cursor)
        assertEquals(9L, full.head)
        assertTrue(full.entities[1].deleted)
        assertEquals("private", full.entities[1].payload().getString("text"))
        full.entities[1].payload().put("text", "changed")
        assertEquals("private", full.entities[1].payload().getString("text"))
        val end = CoordinatorCodec.delta(page(9, 9, entity("c", 8)).toString(), identity, 5, 2)
        assertEquals(9L, end.cursor)
        assertEquals(9L, CoordinatorCodec.delta(page(9, 9).toString(), identity, 9, 2).cursor)
    }

    @Test fun deltaRejectsCursorLossCrossLibraryAndWrongTypes() {
        invalid { CoordinatorCodec.delta(page(9, 9, entity("a", 3), entity("b", 5)).toString(), identity, 1, 2) }
        invalid { CoordinatorCodec.delta(page(9, 5, entity("a", 5)).toString(), identity, 1, 2) }
        invalid { CoordinatorCodec.delta(page(9, 5, entity("a", 5), entity("b", 3)).toString(), identity, 1, 2) }
        invalid { CoordinatorCodec.delta(page(9, 5, entity("a", 3), entity("a", 5)).toString(), identity, 1, 2) }
        invalid { CoordinatorCodec.delta(page(9, 9, entity("a", 1)).toString(), identity, 1, 2) }
        invalid { CoordinatorCodec.delta(page(9, 9).put("libraryId", UUID.randomUUID().toString()).toString(), identity, 1, 2) }
        invalid { CoordinatorCodec.delta(page(9, 9).put("cursor", "9").toString(), identity, 1, 2) }
        invalid { CoordinatorCodec.pull(identity, 0, 101) }
        invalid { CoordinatorCodec.pull(identity, -1, 1) }
    }
}
