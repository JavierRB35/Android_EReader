package com.javierrb35.bibliotecaia.ai

import java.io.IOException

/**
 * Scriptura Android parity: Gemini first; a single Groq backup attempt only
 * for quota, temporary server unavailability or timeout, and only while
 * the reader's separate consent for the second provider remains active.
 *
 * Never retries configuration, permission, model, parsing or moderation errors.
 * The caller closes over one immutable request and never records failed calls
 * as completed conversation turns or saved book synopses.
 */
internal object AiFallbackRouter {

    class BothUnavailableException(cause: Throwable) : IOException(
        "Gemini no ha podido responder y el respaldo Groq tampoco está disponible. " +
            "Comprueba las cuotas, la conexión y las claves en Ajustes.", cause)

    fun eligible(error: AiClients.AiException): Boolean = when (error.kind) {
        AiClients.AiErrorKind.QUOTA,
        AiClients.AiErrorKind.SERVER,
        AiClients.AiErrorKind.TIMEOUT -> true
        else -> false
    }

    /**
     * A configured Groq-only app keeps its existing direct-provider behavior.
     * Gemini+Groq always starts at Gemini; backup requires explicit consent.
     * [fallbackAllowed] is re-evaluated immediately before the second request.
     */
    fun ask(
        geminiConfigured: Boolean,
        groqConfigured: Boolean,
        fallbackAllowed: () -> Boolean,
        generate: (String) -> AiClients.Answer
    ): AiClients.Answer {
        if (!geminiConfigured) {
            if (!groqConfigured) throw AiClients.AiException(
                AiClients.AiErrorKind.INVALID_KEY,
                "Configura una clave Gemini o Groq en Ajustes.")
            return generate(AiClients.GROQ)
        }
        try {
            return generate(AiClients.GEMINI)
        } catch (original: AiClients.AiException) {
            if (!eligible(original) || !groqConfigured || !fallbackAllowed()) throw original
            try {
                return generate(AiClients.GROQ)
            } catch (backup: Exception) {
                if (backup is java.util.concurrent.CancellationException) throw backup
                val combined = BothUnavailableException(backup)
                combined.addSuppressed(original)
                throw combined
            }
        }
    }
}
