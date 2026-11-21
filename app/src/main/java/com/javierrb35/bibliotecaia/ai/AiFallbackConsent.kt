package com.javierrb35.bibliotecaia.ai

import android.content.Context

/**
 * A separate, revocable authorization for automatic transmission to Groq.
 * A stored Groq key alone is NOT consent to forward a Gemini consultation.
 *
 * Identical to Scriptura's privacy rule: rekeying or removing Groq revokes it.
 */
internal object AiFallbackConsent {
    private const val PREFS = "bibliotecaia_ai_fallback_consent"
    private const val KEY = "groq_fallback_authorized"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false) &&
            !AiKeyStore.getKey(context, AiClients.GROQ).isNullOrBlank()

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled) require(!AiKeyStore.getKey(context, AiClients.GROQ).isNullOrBlank()) {
            "Guarda primero una clave Groq válida."
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    fun revoke(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, false).apply()
    }
}
