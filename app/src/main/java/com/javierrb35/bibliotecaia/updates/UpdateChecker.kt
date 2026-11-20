package com.javierrb35.bibliotecaia.updates

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Comprobador de actualizaciones (Prompt Maestro §17): consulta GitHub Releases (repositorio
 * público, sin autenticación) y compara versiones semánticas. La instalación SIEMPRE es
 * manual por el usuario desde la página de la release — nunca silenciosa (§17.2).
 */
object UpdateChecker {

    const val RELEASES_URL = "https://api.github.com/repos/JavierRB35/Android_EReader/releases/latest"
    private const val TIMEOUT_MS = 10_000

    data class ReleaseInfo(val tagName: String, val versionName: String, val notes: String, val htmlUrl: String)

    /** Comparación semántica sencilla: 1 si [candidate] es mayor que [current]. */
    fun isNewer(current: String, candidate: String): Boolean {
        val a = parse(current)
        val b = parse(candidate)
        for (i in 0 until maxOf(a.size, b.size)) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return right > left
        }
        return false
    }

    private fun parse(version: String): List<Int> =
        version.removePrefix("v").split(".").map { it.trim().toIntOrNull() ?: 0 }

    /**
     * Consulta la última release. Devuelve null si no hay release publicada, hay error de
     * red o el formato no es reconocido — el resultado nunca bloquea el uso de la app.
     */
    fun latestRelease(currentVersion: String): ReleaseInfo? {
        return try {
            val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (connection.responseCode !in 200..299) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
                val tag = json.optString("tag_name").trim()
                val version = tag.removePrefix("v")
                if (!version.matches(Regex("""\d+(\.\d+)*"""))) return null
                if (!isNewer(currentVersion, version)) return null
                ReleaseInfo(
                    tagName = tag,
                    versionName = version,
                    notes = json.optString("body").take(600),
                    htmlUrl = json.optString("html_url"),
                )
            } finally {
                connection.disconnect()
            }
        } catch (_: Exception) {
            null // sin conexión u respuesta inválida: comprobación silenciosamente fallida
        }
    }
}
