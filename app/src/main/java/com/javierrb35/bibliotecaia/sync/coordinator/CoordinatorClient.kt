package com.javierrb35.bibliotecaia.sync.coordinator

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

internal enum class CoordinatorOperation { CREATE, ISSUE_PAIRING, JOIN, APPLY, PULL, REVOKE }

internal enum class ConnectionFailure { NETWORK, AUTHORIZATION, CONFLICT, RATE_LIMIT, SERVER, INVALID_RESPONSE, REJECTED }

internal class CoordinatorConnectionException(val failure: ConnectionFailure) :
    IOException("No se pudo completar la operación del coordinador: " + failure.name)

/** Routes are provided by the trusted gateway configuration, not by remote payloads. */
internal class CoordinatorRoutes(private val origin: URL, paths: Map<CoordinatorOperation, String>) {
    private val routes = paths.toMap()
    init {
        require(origin.protocol == "https" && origin.host.isNotBlank() &&
            origin.userInfo == null && origin.query == null && origin.ref == null &&
            origin.path in listOf("", "/")) { "Origen HTTPS inválido" }
        require(routes.keys == CoordinatorOperation.entries.toSet()) { "Rutas incompletas" }
        require(routes.values.all { it.matches(Regex("/[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*")) }) {
            "Ruta de coordinador inválida"
        }
    }
    fun url(operation: CoordinatorOperation): URL = URL(origin, routes.getValue(operation))
}

/** No default endpoint: activation requires reviewed, explicitly configured gateway. */
internal fun interface CoordinatorTransport {
    fun post(operation: CoordinatorOperation, identity: CoordinatorIdentity, body: JSONObject): String
}

internal class HttpsCoordinatorTransport(private val routes: CoordinatorRoutes) : CoordinatorTransport {
    override fun post(operation: CoordinatorOperation, identity: CoordinatorIdentity, body: JSONObject): String {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > CoordinatorCodec.MAX_PAYLOAD_BYTES + 4096)
            throw CoordinatorConnectionException(ConnectionFailure.REJECTED)
        var connection: HttpsURLConnection? = null
        try {
            val conn = routes.url(operation).openConnection() as HttpsURLConnection
            connection = conn
            // Do not forward credentials even to a redirected same-origin URL.
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer " + identity.credential())
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cache-Control", "no-store")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val status = conn.responseCode
            if (status !in 200..299) throw CoordinatorConnectionException(classify(status))
            if (status == 204 && operation == CoordinatorOperation.REVOKE) return ""
            val type = conn.contentType.orEmpty().substringBefore(';').trim()
            if (!type.equals("application/json", ignoreCase = true))
                throw CoordinatorConnectionException(ConnectionFailure.INVALID_RESPONSE)
            if (conn.contentLengthLong > CoordinatorCodec.MAX_RESPONSE_BYTES)
                throw CoordinatorConnectionException(ConnectionFailure.INVALID_RESPONSE)
            val raw = conn.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > CoordinatorCodec.MAX_RESPONSE_BYTES)
                        throw CoordinatorConnectionException(ConnectionFailure.INVALID_RESPONSE)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
            } catch (_: Exception) {
                throw CoordinatorConnectionException(ConnectionFailure.INVALID_RESPONSE)
            }
        } catch (e: CoordinatorConnectionException) {
            throw e
        } catch (_: IOException) {
            // Do not propagate URL, server messages, stack causes or private bodies.
            throw CoordinatorConnectionException(ConnectionFailure.NETWORK)
        } finally {
            bytes.fill(0)
            connection?.disconnect()
        }
    }

    companion object {
        fun classify(status: Int): ConnectionFailure = when (status) {
            401, 403 -> ConnectionFailure.AUTHORIZATION
            409 -> ConnectionFailure.CONFLICT
            429 -> ConnectionFailure.RATE_LIMIT
            in 500..599 -> ConnectionFailure.SERVER
            in 300..399 -> ConnectionFailure.INVALID_RESPONSE
            else -> ConnectionFailure.REJECTED
        }
    }
}

/** Blocking I/O: caller must dispatch off main thread. No automatic retries. */
internal class CoordinatorClient(private val transport: CoordinatorTransport) {
    private fun <T> call(operation: CoordinatorOperation, identity: CoordinatorIdentity, body: JSONObject,
                         decode: (String) -> T): T {
        val raw = transport.post(operation, identity, body)
        return try { decode(raw) } catch (_: ProtocolViolation) {
            throw CoordinatorConnectionException(ConnectionFailure.INVALID_RESPONSE)
        }
    }
    fun create(identity: CoordinatorIdentity) =
        call(CoordinatorOperation.CREATE, identity, CoordinatorCodec.create(identity)) {
            CoordinatorCodec.membership(it, identity)
        }
    fun join(identity: CoordinatorIdentity, code: String) =
        call(CoordinatorOperation.JOIN, identity, CoordinatorCodec.join(identity, code)) {
            CoordinatorCodec.membership(it, identity)
        }
    fun issuePairing(identity: CoordinatorIdentity): PairingOffer =
        call(CoordinatorOperation.ISSUE_PAIRING, identity, CoordinatorCodec.issuePairing(identity)) {
            CoordinatorCodec.pairing(it, identity)
        }
    fun apply(identity: CoordinatorIdentity, change: PendingChange): ApplyOutcome =
        call(CoordinatorOperation.APPLY, identity, CoordinatorCodec.apply(identity, change)) {
            CoordinatorCodec.applied(it, change)
        }
    fun pull(identity: CoordinatorIdentity, after: Long, limit: Int): DeltaPage =
        call(CoordinatorOperation.PULL, identity, CoordinatorCodec.pull(identity, after, limit)) {
            CoordinatorCodec.delta(it, identity, after, limit)
        }
    fun revoke(identity: CoordinatorIdentity, target: UUID) =
        call(CoordinatorOperation.REVOKE, identity, CoordinatorCodec.revoke(identity, target)) {
            if (it.isNotEmpty()) throw ProtocolViolation()
        }
}
