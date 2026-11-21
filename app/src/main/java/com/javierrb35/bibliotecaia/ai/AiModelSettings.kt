package com.javierrb35.bibliotecaia.ai

import android.content.Context

/**
 * Port of Scriptura GeminiClient.model(): a user-changeable model ID
 * preserves a working API key when a model is retired or unavailable.
 * Does not guess which IDs the user's personal account can access.
 */
internal object AiModelSettings {
    private const val PREFS = "bibliotecaia_ai_model_settings"
    private const val GEMINI = "gemini_model"
    private val validName = Regex("[a-zA-Z0-9._-]{3,80}")

    fun valid(id: String) = validName.matches(id)

    fun current(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(GEMINI, null)
        return saved?.takeIf(::valid) ?: AiClients.GEMINI_MODEL
    }

    fun save(context: Context, newId: String) {
        val id = newId.trim()
        require(valid(id)) { "ID de modelo Gemini no válido" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(GEMINI, id).apply()
    }
}
