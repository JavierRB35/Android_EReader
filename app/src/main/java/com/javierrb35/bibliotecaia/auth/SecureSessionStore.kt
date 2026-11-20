package com.javierrb35.bibliotecaia.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Session only, never library data; excluded from OS backup and manual library exports. */
internal class SecureSessionStore(context: Context, private val alias: String = "biblioteca.account.session.v1") {
    private val file = AtomicFile(File(context.noBackupFilesDir, "biblioteca-account-session.v1"))

    fun load(projectUrl: String): AccountSession? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        try {
            val bytes = file.openRead().use { input ->
                val buffer = ByteArray(65537)
                var size = 0
                while (size < buffer.size) {
                    val n = input.read(buffer, size, buffer.size - size)
                    if (n < 0) break
                    size += n
                }
                require(size in 30..65536)
                buffer.copyOf(size)
            }
            require(bytes[0] == 1.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(create = false), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(13, bytes.size)), Charsets.UTF_8))
            if (json.getString("project") != projectUrl) throw IllegalStateException("Project mismatch")
            return AccountSession(json.getString("user"), json.getString("email"), json.getString("access"), json.getString("refresh"), json.getLong("expires"), projectUrl)
        } catch (_: Exception) {
            throw AccountFailure(AccountFailure.Reason.SECURE_STORAGE)
        }
    }

    fun save(session: AccountSession) {
        var stream: java.io.FileOutputStream? = null
        try {
            val json = JSONObject().put("user", session.userId).put("email", session.email)
                .put("access", session.accessToken).put("refresh", session.refreshToken)
                .put("expires", session.expiresAtSeconds).put("project", session.projectUrl)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key(create = true))
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            require(cipher.iv.size == 12)
            val encrypted = byteArrayOf(1) + cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            require(encrypted.size <= 65536)
            stream = file.startWrite()
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (_: Exception) {
            if (stream != null) file.failWrite(stream)
            throw AccountFailure(AccountFailure.Reason.SECURE_STORAGE)
        }
    }

    fun clear() {
        file.delete()
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) throw AccountFailure(AccountFailure.Reason.SECURE_STORAGE)
    }

    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create)
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
}
