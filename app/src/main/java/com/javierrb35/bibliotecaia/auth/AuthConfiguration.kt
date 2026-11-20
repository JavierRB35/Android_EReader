package com.javierrb35.bibliotecaia.auth

import com.javierrb35.bibliotecaia.BuildConfig
import org.json.JSONObject
import java.util.Base64

class AuthConfiguration(
    val projectUrl: String,
    internal val publicKey: String,
    val googleWebClientId: String
) {
    /** Email sign-up/sign-in only needs a backend. Google OAuth additionally needs a Web Client ID. */
    val backendConfigured: Boolean get() = AuthPolicy.validProjectUrl(projectUrl) && validPublicKey()
    val googleConfigured: Boolean get() = backendConfigured && AuthPolicy.validClientId(googleWebClientId)
    val configured: Boolean get() = googleConfigured

    private fun validPublicKey(): Boolean = try {
        if (publicKey.startsWith("sb_publishable_")) {
            publicKey.matches(Regex("sb_publishable_[a-zA-Z0-9_-]{10,}"))
        } else {
            val parts = publicKey.split('.')
            parts.size == 3 && JSONObject(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)).optString("role") == "anon"
        }
    } catch (_: Exception) { false }

    companion object {
        fun fromBuild() = AuthConfiguration(BuildConfig.SUPABASE_URL.trimEnd('/'), BuildConfig.SUPABASE_PUBLIC_KEY, BuildConfig.GOOGLE_WEB_CLIENT_ID)
    }
}

/** Intentionally not a data class: generated toString/copy must not disclose credentials. */
class AccountSession(
    val userId: String,
    val email: String,
    internal val accessToken: String,
    internal val refreshToken: String,
    val expiresAtSeconds: Long,
    internal val projectUrl: String
)

class AccountFailure(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_CONFIGURED, NO_SESSION, NETWORK, REJECTED, RATE_LIMIT, SERVICE, INVALID_RESPONSE, SECURE_STORAGE, INVALID_INPUT }
}
