package com.javierrb35.bibliotecaia.sync

import android.content.Context
import com.javierrb35.bibliotecaia.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cliente REST de Supabase (PostgREST) con límites acotados y errores mapeados.
 * Cabeceras: apikey (clave pública) y Authorization Bearer (JWT del usuario autenticado).
 * Nunca envía la clave de servicio ni acepta configuración no pública (§16).
 */
class SupabaseRestClient(
    private val projectUrl: String,
    private val publicKey: String,
) {
    init {
        require(projectUrl.startsWith("https://")) { "La URL de Supabase debe ser HTTPS" }
        require(publicKey.isNotBlank()) { "Falta la clave pública de Supabase" }
    }

    enum class Method { GET, POST, PATCH }

    sealed class Result {
        data class Ok(val status: Int, val body: String) : Result()
        data class Failure(val status: Int, val message: String) : Result()
        object Offline : Result() // sin red: la cola local conserva los cambios
    }

    /**
     * Llamada PostgREST. [query] p. ej. "books?select=*&id=eq.<uuid>" o "" para colecciones.
     * [rows] serializa el cuerpo para POST/PATCH (upsert con Prefer merge-duplicates).
     */
    fun execute(
        method: Method,
        table: String,
        query: String,
        jwt: String?,
        rows: JSONArray? = null,
        upsert: Boolean = false,
    ): Result = try {
        val connection = URL("$projectUrl/rest/v1/$table${if (query.isBlank()) "" else "?$query"}")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.requestMethod = method.name
        connection.setRequestProperty("apikey", publicKey)
        if (!jwt.isNullOrBlank()) connection.setRequestProperty("Authorization", "Bearer $jwt")
        connection.setRequestProperty("Accept", "application/json")
        if (rows != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            // Idempotencia: repetir un upsert no duplica (las claves son UUID del dispositivo)
            connection.setRequestProperty(
                "Prefer",
                if (upsert) "resolution=merge-duplicates,return=minimal" else "return=minimal",
            )
            connection.outputStream.use { it.write(rows.toString().toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val body = ((if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: "")
        connection.disconnect()
        if (status in 200..299) Result.Ok(status, body)
        else Result.Failure(status, body.ifBlank { "HTTP $status" })
    } catch (_: IOException) {
        Result.Offline
    } catch (_: Exception) {
        Result.Failure(-1, "Error de cliente")
    }

    fun pullSince(table: String, sinceEpochMs: Long, jwt: String): Result =
        execute(
            Method.GET, table,
            "select=*&updated_at=gt.${iso(sinceEpochMs)}&order=updated_at.asc",
            jwt,
        )

    fun pushUpsert(table: String, rows: JSONArray, jwt: String): Result =
        execute(Method.POST, table, "", jwt, rows, upsert = true)

    private fun iso(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).toString()

    companion object {
        const val TIMEOUT_MS = 10_000

        /** Cliente listo solo si el propietario configuró URL y clave pública (sin secretos). */
        fun fromBuild(): SupabaseRestClient? {
            val url = BuildConfig.SUPABASE_URL.trim()
            val key = BuildConfig.SUPABASE_PUBLIC_KEY.trim()
            if (!url.startsWith("https://") || key.isBlank()) return null
            return runCatching { SupabaseRestClient(url, key) }.getOrNull()
        }
    }
}
