package com.javierrb35.bibliotecaia.ai

import android.content.Context

/** Default for new setups: Gemini first, Groq backup when a key is present.
 * Preserve legacy disabled authorizations (they cannot be distinguished from
 * intentional revocation), and all explicit choices made in the new screen.
 */
internal object AiFallbackConsent {
    private const val PREFS = "bibliotecaia_ai_fallback_consent"
    private const val LEGACY = "groq_fallback_authorized"
    private const val KEY = "groq_fallback_preferred_v2"

    fun preferred(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY)) return prefs.getBoolean(KEY, true)
        if (!AiKeyStore.getKey(context, AiClients.GROQ).isNullOrBlank() && prefs.contains(LEGACY))
            return prefs.getBoolean(LEGACY, false)
        return true
    }

    fun enabled(context: Context): Boolean =
        preferred(context) && !AiKeyStore.getKey(context, AiClients.GROQ).isNullOrBlank()

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }

    fun revoke(context: Context) = setEnabled(context, false)
}
