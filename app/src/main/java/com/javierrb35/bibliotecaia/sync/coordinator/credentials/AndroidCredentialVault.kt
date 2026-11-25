package com.javierrb35.bibliotecaia.sync.coordinator.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The only production vault for enrollment. AES-256-GCM with nonexportable
 * AndroidKeyStore key. Ciphertext in noBackupFilesDir, not SharedPreferences,
 * Room, app ZIP, Drive, logs or UI. AtomicFile preserves the prior record if
 * a write fails. No automatic reset or replacement of an inaccessible key. */
internal class AndroidCredentialVault(context: Context) : CredentialVault {
    private val file = File(context.applicationContext.noBackupFilesDir, "coordinator-identity.v1")
    private val atomic = AtomicFile(file)
    private val alias = "bibliotecaia_coordinator_identity_v1"
    private val aad = "BibliotecaIA/coordinator/identity/v1".toByteArray(Charsets.UTF_8)

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private fun hasArtifact(): Boolean = file.exists() ||
        File(file.path + ".bak").exists() || File(file.path + ".new").exists()

    private fun existingKey(store: KeyStore): SecretKey =
        store.getKey(alias, null) as? SecretKey ?: throw CredentialStoreFailure()

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    override fun read(): ByteArray? {
        try {
            val store = keystore()
            val encrypted = try {
                atomic.openRead().use { input ->
                    val buffer = ByteArray(8193)
                    var size = 0
                    while (size < buffer.size) {
                        val n = input.read(buffer, size, buffer.size - size)
                        if (n == -1) break
                        if (n == 0) throw CredentialStoreFailure()
                        size += n
                    }
                    require(size in 30..8192)
                    buffer.copyOf(size)
                }
            } catch (_: FileNotFoundException) {
                if (!hasArtifact() && !store.containsAlias(alias)) return null
                throw CredentialStoreFailure()
            }
            require(encrypted[0] == 1.toByte() && encrypted[1] == 12.toByte())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, existingKey(store), GCMParameterSpec(128, encrypted.copyOfRange(2,14)))
            cipher.updateAAD(aad)
            return cipher.doFinal(encrypted, 14, encrypted.size - 14)
        } catch (_: Exception) {
            throw CredentialStoreFailure()
        }
    }

    override fun write(value: ByteArray) {
        try {
            require(value.size in 1..4096)
            val store = keystore()
            // A present record with missing key must never be overwritten.
            val key = if (store.containsAlias(alias)) existingKey(store)
                else if (!hasArtifact()) createKey() else throw CredentialStoreFailure()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad)
            require(cipher.iv.size == 12)
            val bytes = byteArrayOf(1, 12) + cipher.iv + cipher.doFinal(value)
            val stream = atomic.startWrite()
            try {
                stream.write(bytes)
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                atomic.failWrite(stream)
                throw e
            }
            // AtomicFile.finishWrite reports some filesystem failures via logs.
            // Verify the persisted record before a caller can send any request.
            val checked = read() ?: throw CredentialStoreFailure()
            try {
                if (!checked.contentEquals(value)) throw CredentialStoreFailure()
            } finally { checked.fill(0) }
        } catch (_: Exception) {
            throw CredentialStoreFailure()
        }
    }
}
