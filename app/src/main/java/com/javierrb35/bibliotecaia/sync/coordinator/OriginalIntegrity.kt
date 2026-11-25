package com.javierrb35.bibliotecaia.sync.coordinator

import java.io.InputStream
import java.security.MessageDigest

/** Verification of exact original bytes, not merely the name or metadata of a file.
 * This is deliberately read-only; it neither creates nor replaces any SAF document.
 */
internal object OriginalIntegrity {
    enum class Result { VERIFIED, SIZE_MISMATCH, HASH_MISMATCH, UNREADABLE }
    fun verify(expectedHash: String, expectedSize: Long, open: () -> InputStream): Result {
        require(expectedHash.matches(Regex("[a-f0-9]{64}")) && expectedSize >= 0) { "Metadatos inválidos" }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            open().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (count == 0) return Result.UNREADABLE
                    if (count > expectedSize - total) return Result.SIZE_MISMATCH
                    total += count
                    digest.update(buffer, 0, count)
                }
            }
            if (total != expectedSize) return Result.SIZE_MISMATCH
            val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            if (actual == expectedHash) Result.VERIFIED else Result.HASH_MISMATCH
        } catch (_: java.io.IOException) {
            Result.UNREADABLE
        } catch (_: SecurityException) {
            Result.UNREADABLE
        }
    }
}
