package com.javierrb35.bibliotecaia.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Adaptadores de proveedores IA (BYOK, Prompt Maestro §12-13): Gemini y Groq.
 * Los cuerpos de petición se construyen con funciones puras (testeables) y las
 * llamadas usan HTTPS con timeouts acotados. Errores diferenciados para diagnóstico:
 * red, clave inválida, cuota, permisos, modelo y servidor.
 */
object AiClients {

    const val GEMINI = "gemini"
    const val GROQ = "groq"

    const val GEMINI_MODEL = "gemini-3.6-flash"
    const val GROQ_MODEL = "openai/gpt-oss-20b"

    const val GEMINI_KEYS_URL = "https://aistudio.google.com/apikey"
    const val GROQ_KEYS_URL = "https://console.groq.com/keys"
    const val GEMINI_DOCS_URL = "https://ai.google.dev/gemini-api/docs"
    const val GROQ_DOCS_URL = "https://console.groq.com/docs"

    private const val TIMEOUT_MS = 30_000

    enum class AiErrorKind { NETWORK, INVALID_KEY, QUOTA, PERMISSION, MODEL, SERVER, PARSE }

    class AiException(val kind: AiErrorKind, message: String) : IOException(message)

    data class Answer(val text: String, val provider: String, val model: String)

    // ---------- cuerpos de petición (puros, testeables) ----------

    const val SYSTEM_INSTRUCTION =
        "Eres el asistente de lectura de Biblioteca IA. Responde SOLO con información " +
        "contenida en los FRAGMENTOS del libro que se te entregan, citando el fragmento " +
        "usado como [1], [2]… según su numeración. Si la respuesta no está en los " +
        "fragmentos, responde primero exactamente «No aparece en el libro.» y después, " +
        "si puedes aportar contexto externo, añádelo en un párrafo que comience por " +
        "«Externo:». Sé conciso."

    fun geminiBody(question: String, fragments: List<String>, history: List<Pair<String, String>>): JSONObject {
        val contents = JSONArray()
        history.forEach { (role, text) ->
            contents.put(JSONObject().put("role", if (role == "user") "user" else "model")
                .put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        contents.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text",
            contextBlock(fragments) + "\n\nPregunta: " + question))))
        return JSONObject()
            .put("contents", contents)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION))))
            .put("generationConfig", JSONObject().put("temperature", 0.2))
    }

    fun groqBody(question: String, fragments: List<String>, history: List<Pair<String, String>>): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", SYSTEM_INSTRUCTION))
        history.forEach { (role, text) ->
            messages.put(JSONObject().put("role", if (role == "user") "user" else "assistant").put("content", text))
        }
        messages.put(JSONObject().put("role", "user")
            .put("content", contextBlock(fragments) + "\n\nPregunta: " + question))
        return JSONObject()
            .put("model", GROQ_MODEL)
            .put("messages", messages)
            .put("temperature", 0.2)
    }

    fun contextBlock(fragments: List<String>): String = buildString {
        append("FRAGMENTOS DEL LIBRO:\n")
        fragments.forEachIndexed { i, fragment ->
            append("\n[${i + 1}] ").append(fragment)
        }
    }

    // ---------- parseo de respuestas (puro, testeable) ----------

    fun parseGemini(body: String): String {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates") ?: throw AiException(AiErrorKind.PARSE, "Respuesta sin candidatos")
        val first = candidates.optJSONObject(0) ?: throw AiException(AiErrorKind.PARSE, "Respuesta vacía")
        val content = first.optJSONObject("content") ?: throw AiException(AiErrorKind.PARSE, "Sin contenido")
        val parts = content.optJSONArray("parts") ?: throw AiException(AiErrorKind.PARSE, "Sin partes")
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            text.append(parts.optJSONObject(i)?.optString("text") ?: "")
        }
        return text.toString().ifBlank { throw AiException(AiErrorKind.PARSE, "Respuesta vacía") }
    }

    fun parseGroq(body: String): String {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: throw AiException(AiErrorKind.PARSE, "Respuesta sin opciones")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiException(AiErrorKind.PARSE, "Respuesta vacía")
        return message.optString("content").ifBlank { throw AiException(AiErrorKind.PARSE, "Respuesta vacía") }
    }

    // ---------- llamada real ----------

    fun ask(provider: String, key: String, question: String, fragments: List<String>,
            history: List<Pair<String, String>>): Answer {
        val route: Triple<String, JSONObject, (String) -> String> = when (provider) {
            GEMINI -> Triple(
                "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent",
                geminiBody(question, fragments, history),
                ::parseGemini,
            )
            GROQ -> Triple(
                "https://api.groq.com/openai/v1/chat/completions",
                groqBody(question, fragments, history),
                ::parseGroq,
            )
            else -> throw AiException(AiErrorKind.INVALID_KEY, "Proveedor desconocido: $provider")
        }
        val connection = URL(route.first).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json")
        if (provider == GEMINI) connection.setRequestProperty("x-goog-api-key", key)
        else connection.setRequestProperty("Authorization", "Bearer $key")
        try {
            connection.outputStream.use { it.write(route.second.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val payload = ((if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: "")
            if (status !in 200..299) throw classifyHttp(status, payload)
            return Answer(route.third(payload), provider, if (provider == GEMINI) GEMINI_MODEL else GROQ_MODEL)
        } catch (e: AiException) {
            throw e
        } catch (_: java.io.IOException) {
            throw AiException(AiErrorKind.NETWORK, "Sin conexión con el proveedor.")
        } finally {
            connection.disconnect()
        }
    }

    fun classifyHttp(status: Int, payload: String): AiException {
        val detail = extractProviderMessage(payload)
        return when {
            status == 401 || (status == 400 && detail.contains("API key", ignoreCase = true)) ->
                AiException(AiErrorKind.INVALID_KEY, "Clave inválida o mal formada.")
            status == 403 ->
                AiException(AiErrorKind.PERMISSION, "Clave sin permiso o restringida.")
            status == 429 ->
                AiException(AiErrorKind.QUOTA, "Cuota o límite de uso agotado; reintenta más tarde.")
            status == 404 ->
                AiException(AiErrorKind.MODEL, "El modelo solicitado no está disponible.")
            status in 500..599 ->
                AiException(AiErrorKind.SERVER, "El proveedor tiene un problema temporal.")
            else -> AiException(AiErrorKind.SERVER, "Error del proveedor (HTTP $status). $detail")
        }
    }

    private fun extractProviderMessage(payload: String): String = try {
        val root = JSONObject(payload)
        root.optJSONObject("error")?.optString("message")
            ?: root.optString("message")
    } catch (_: Exception) {
        payload.take(200)
    }
}
