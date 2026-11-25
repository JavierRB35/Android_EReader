package com.javierrb35.bibliotecaia.sync.coordinator

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID

class CoordinatorClientTest {
    private val identity = CoordinatorIdentity.restore(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64))
    private val routes = CoordinatorOperation.entries.associateWith { "/v1/" + it.name.lowercase() }

    @Test fun onlyTrustedHttpsRoutes() {
        val r = CoordinatorRoutes(URL("https://example.invalid"), routes)
        assertEquals("https://example.invalid/v1/create", r.url(CoordinatorOperation.CREATE).toString())
        for (origin in listOf("http://example.invalid", "https://user@example.invalid", "https://example.invalid?q=x")) {
            assertThrows(IllegalArgumentException::class.java) { CoordinatorRoutes(URL(origin), routes) }
        }
        for (path in listOf("//evil.invalid", "/v1/../evil", "/v1?q=x", "/v1/%2f")) {
            assertThrows(IllegalArgumentException::class.java) {
                CoordinatorRoutes(URL("https://example.invalid"), routes + (CoordinatorOperation.CREATE to path))
            }
        }
        assertEquals(ConnectionFailure.INVALID_RESPONSE, HttpsCoordinatorTransport.classify(302))
        assertEquals(ConnectionFailure.AUTHORIZATION, HttpsCoordinatorTransport.classify(403))
        assertEquals(ConnectionFailure.RATE_LIMIT, HttpsCoordinatorTransport.classify(429))
        assertEquals(ConnectionFailure.SERVER, HttpsCoordinatorTransport.classify(503))
    }

    @Test fun createJoinAndApplyUseSameIdentityAndNeverHideFailures() {
        var calls = 0
        val client = CoordinatorClient(CoordinatorTransport { operation, id, body ->
            calls++
            assertSame(identity, id)
            assertFalse(body.has("token"))
            assertEquals(id.libraryId.toString(), body.getString("libraryId"))
            when (operation) {
                CoordinatorOperation.CREATE, CoordinatorOperation.JOIN -> CoordinatorCodec.create(id).toString()
                CoordinatorOperation.APPLY -> """{"status":"conflict","revision":5,"deleted":true}"""
                else -> throw CoordinatorConnectionException(ConnectionFailure.NETWORK)
            }
        })
        client.create(identity)
        client.join(identity, "b".repeat(64))
        val change = PendingChange(UUID.randomUUID(), EntityKey(EntityKind.NOTE, "n1"), 3, ChangeAction.PUT, JSONObject())
        assertEquals(ApplyOutcome.Conflict(5, true), client.apply(identity, change))
        val failure = assertThrows(CoordinatorConnectionException::class.java) { client.pull(identity, 0, 10) }
        assertEquals(ConnectionFailure.NETWORK, failure.failure)
        assertEquals(4, calls)
        val bad = CoordinatorClient(CoordinatorTransport { _, _, _ -> """{"private":"secret"}""" })
        val invalid = assertThrows(CoordinatorConnectionException::class.java) { bad.create(identity) }
        assertEquals(ConnectionFailure.INVALID_RESPONSE, invalid.failure)
        assertNull(invalid.cause)
        assertFalse(invalid.message.orEmpty().contains("secret"))
    }

    @Test fun originalsMustMatchBytesAndSize() {
        val bytes = "synthetic book".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(OriginalIntegrity.Result.VERIFIED, OriginalIntegrity.verify(hash, bytes.size.toLong()) {
            ByteArrayInputStream(bytes)
        })
        assertEquals(OriginalIntegrity.Result.SIZE_MISMATCH, OriginalIntegrity.verify(hash, 1) {
            ByteArrayInputStream(bytes)
        })
        assertEquals(OriginalIntegrity.Result.HASH_MISMATCH, OriginalIntegrity.verify("a".repeat(64), bytes.size.toLong()) {
            ByteArrayInputStream(bytes)
        })
        assertEquals(OriginalIntegrity.Result.UNREADABLE, OriginalIntegrity.verify(hash, bytes.size.toLong()) {
            throw IOException("private path")
        })
    }
}
