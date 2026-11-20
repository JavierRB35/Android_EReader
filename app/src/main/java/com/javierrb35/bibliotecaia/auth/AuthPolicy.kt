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
    /** Conservative UI validation; server remains the authority for account creation. */
    fun validEmail(value: String): Boolean {
        if (value.length !in 6..254 || value.any { it.isWhitespace() }) return false
        val at = value.indexOf('@')
        if (at !in 1..63 || at != value.lastIndexOf('@')) return false
        val local = value.substring(0, at)
        val domain = value.substring(at + 1)
        return local.first() != '.' && local.last() != '.' && ".." !in local &&
            domain.length in 4..253 && domain.contains('.') &&
            domain.split('.').all { it.length in 1..63 && it.first().isLetterOrDigit() &&
                it.last().isLetterOrDigit() && it.all { ch -> ch.isLetterOrDigit() || ch == '-' } } &&
            local.all { it.isLetterOrDigit() || it in ".!#$%&'*+-/=?^_`{|}~" }
    }
    fun validPassword(value: String): Boolean = value.length in 8..128

    fun needsRefresh(expiresAtSeconds: Long, nowSeconds: Long): Boolean = expiresAtSeconds <= nowSeconds + 60
    fun nonce(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }
    fun nonceHash(raw: String): String = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
