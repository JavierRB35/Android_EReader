package com.javierrb35.bibliotecaia.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Almacenamiento cifrado de claves IA del usuario (BYOK, Prompt Maestro §13).
 * AES-256-GCM con clave no exportable en AndroidKeyStore; el texto plano jamás
 * toca SharedPreferences, copias de seguridad, logs ni el repositorio.
 */
object AiKeyStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS_PREFIX = "bibliotecaia_ai_"
    private const val PREFS = "ai_keys_enc"
    private const val GCM_TAG_BITS = 128

    fun providers(): List<String> = listOf("gemini", "groq")

    fun saveKey(context: Context, provider: String, plainKey: String): Boolean {
        return try {
            val alias = alias(provider)
            val secret = getOrCreateKey(alias)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secret)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(plainKey.trim().toByteArray(Charsets.UTF_8))
            val payload = Base64.encodeToString(iv, Base64.NO_WRAP) + ":" +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            prefs(context).edit().putString(alias, payload).apply()
            // As in Scriptura: a new Groq key invalidates the prior forwarding consent.
            if (provider == AiClients.GROQ) AiFallbackConsent.revoke(context)
            true
        } catch (e: Exception) {
            Log.w("AiKeyStore", "no se pudo guardar la clave de $provider", e)
            false
        }
    }

    fun getKey(context: Context, provider: String): String? {
        val alias = alias(provider)
        val payload = prefs(context).getString(alias, null) ?: return null
        return try {
            val parts = payload.split(':')
            if (parts.size != 2) return null
            val secret = getOrCreateKey(alias)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(GCM_TAG_BITS, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w("AiKeyStore", "clave de $provider ilegible (Keystore invalidado)", e)
            null
        }
    }

    fun deleteKey(context: Context, provider: String) {
        if (provider == AiClients.GROQ) AiFallbackConsent.revoke(context)
        prefs(context).edit().remove(alias(provider)).apply()
        try {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            if (store.containsAlias(alias(provider))) store.deleteEntry(alias(provider))
        } catch (_: Exception) {
        }
    }

    fun hasKey(context: Context, provider: String): Boolean =
        prefs(context).getString(alias(provider), null) != null

    private fun alias(provider: String) = ALIAS_PREFIX + provider

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getOrCreateKey(alias: String): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
