package com.javierrb35.bibliotecaia.auth

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Real Supabase Auth requests. No library upload, account linking or Drive authorization. */
class AccountRepository(context: Context, val configuration: AuthConfiguration = AuthConfiguration.fromBuild()) {
    private val store = SecureSessionStore(context.applicationContext)

    suspend fun savedSession(): AccountSession? = withContext(Dispatchers.IO) {
        sessionMutex.withLock { store.load(configuration.projectUrl) }
    }

    suspend fun signIn(googleIdToken: String, rawNonce: String): AccountSession = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            requireConfigured()
            require(googleIdToken.length in 1..32000 && rawNonce.length in 32..128)
            val response = request("POST", "/token?grant_type=id_token", JSONObject()
                .put("provider", "google").put("id_token", googleIdToken).put("nonce", rawNonce))
            val session = parseSession(response)
            verifyUser(session)
            store.save(session)
            session
        }
    }

    /** Server validation is required before enabling an online operation. Cached identity is UI-only. */
    suspend fun requireOnlineSession(): AccountSession = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            requireConfigured()
            var session = store.load(configuration.projectUrl) ?: throw AccountFailure(AccountFailure.Reason.NO_SESSION)
            try {
                if (AuthPolicy.needsRefresh(session.expiresAtSeconds, System.currentTimeMillis() / 1000)) {
                    val refreshed = parseSession(request("POST", "/token?grant_type=refresh_token", JSONObject().put("refresh_token", session.refreshToken)))
                    if (refreshed.userId != session.userId) throw AccountFailure(AccountFailure.Reason.INVALID_RESPONSE)
                    // Persist the rotated refresh token before another operation or process death.
                    store.save(refreshed)
                    session = refreshed
                }
                verifyUser(session)
                session
            } catch (e: AccountFailure) {
                if (e.reason == AccountFailure.Reason.REJECTED) store.clear()
                throw e
            }
        }
    }

    /** Returns only after remote revocation succeeds; caller can explicitly choose local-only exit. */
    suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            requireConfigured()
            val session = store.load(configuration.projectUrl)
            if (session != null) {
                request("POST", "/logout?scope=local", JSONObject(), session.accessToken)
            }
            store.clear()
        }
    }

    suspend fun forgetOnThisDevice(): Unit = withContext(Dispatchers.IO) {
        sessionMutex.withLock { store.clear() }
    }

    private fun verifyUser(session: AccountSession) {
        val user = request("GET", "/user", accessToken = session.accessToken)
        val identities = user.optJSONArray("identities")
        val googleIdentity = identities != null && (0 until identities.length()).any {
            identities.optJSONObject(it)?.optString("provider") == "google"
        }
        if (user.optString("id") != session.userId || !googleIdentity || user.optBoolean("is_anonymous", true)) {
            throw AccountFailure(AccountFailure.Reason.REJECTED)
        }
    }

    private fun parseSession(json: JSONObject): AccountSession = try {
        val access = json.getString("access_token")
        val refresh = json.getString("refresh_token")
        val expiresIn = json.getLong("expires_in")
        val user = json.getJSONObject("user")
        val userId = user.getString("id")
        require(UUID.fromString(userId).toString() == userId)
        require(access.length in 10..32000 && refresh.length in 10..32000 && expiresIn in 1..86400)
        AccountSession(userId, user.optString("email").take(320), access, refresh,
            System.currentTimeMillis() / 1000 + expiresIn, configuration.projectUrl)
    } catch (_: Exception) { throw AccountFailure(AccountFailure.Reason.INVALID_RESPONSE) }

    private fun requireConfigured() {
        if (!configuration.configured) throw AccountFailure(AccountFailure.Reason.NOT_CONFIGURED)
    }

    private fun request(method: String, route: String, body: JSONObject? = null, accessToken: String? = null): JSONObject {
        val connection = URL(configuration.projectUrl.trimEnd('/') + "/auth/v1" + route).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15000
            connection.readTimeout = 20000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", configuration.publicKey)
            if (accessToken != null) connection.setRequestProperty("Authorization", "Bearer $accessToken")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                require(bytes.size <= 65536)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw AccountFailure(when (status) {
                400, 401, 403, 422 -> AccountFailure.Reason.REJECTED
                429 -> AccountFailure.Reason.RATE_LIMIT
                else -> AccountFailure.Reason.SERVICE
            })
            if (status == 204) return JSONObject()
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (out.size() + count > 131072) throw AccountFailure(AccountFailure.Reason.INVALID_RESPONSE)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            if (bytes.isEmpty()) return JSONObject()
            return try { JSONObject(String(bytes, Charsets.UTF_8)) }
            catch (_: Exception) { throw AccountFailure(AccountFailure.Reason.INVALID_RESPONSE) }
        } catch (e: AccountFailure) { throw e }
        catch (_: IOException) { throw AccountFailure(AccountFailure.Reason.NETWORK) }
        finally { connection.disconnect() }
    }

    companion object { private val sessionMutex = Mutex() }
}
