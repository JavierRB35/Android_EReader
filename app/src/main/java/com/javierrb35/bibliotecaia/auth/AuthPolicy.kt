package com.javierrb35.bibliotecaia.auth

import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Pure rules kept independent from Android for regression tests. */
object AuthPolicy {
    fun validProjectUrl(value: String): Boolean = try {
        val uri = URI(value)
        uri.scheme == "https" && uri.host?.matches(Regex("[a-z0-9]{10,40}\\.supabase\\.co")) == true &&
            uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
    } catch (_: Exception) { false }

    fun validClientId(value: String) = value.matches(Regex("[0-9]+-[a-zA-Z0-9_-]+\\.apps\\.googleusercontent\\.com"))
    fun needsRefresh(expiresAtSeconds: Long, nowSeconds: Long): Boolean = expiresAtSeconds <= nowSeconds + 60
    fun nonce(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
    fun nonceHash(raw: String): String = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
