package com.javierrb35.bibliotecaia.updates

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Consulta oficial de GitHub Releases del repositorio público, sin clave de API.
 * La aplicación nunca instala una APK sin consentimiento de Android.
 */
object UpdateChecker {
    const val RELEASES_URL = "https://api.github.com/repos/JavierRB35/Android_EReader/releases/latest"
    private const val OFFICIAL_REPO = "https://github.com/JavierRB35/Android_EReader"
    private const val TIMEOUT_MS = 10_000
    private const val MAX_RELEASE_BYTES = 512_000
    private val releaseTag = Regex("""v[0-9]+\.[0-9]+\.[0-9]+""")

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val notes: String,
        val htmlUrl: String,
        val apkUrl: String,
        val sha256Url: String,
    )

    /** Compare semantic versions, preserving the behavior of previous releases. */
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

    /** Pure, unit-testable parser. Never announce partial or untrusted uploads. */
    fun parseLatest(body: String, currentVersion: String): ReleaseInfo? {
        val json = JSONObject(body)
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.optString("tag_name").trim()
        if (!releaseTag.matches(tag) || !isNewer(currentVersion, tag)) return null

        val page = "$OFFICIAL_REPO/releases/tag/$tag"
        if (json.optString("html_url") != page) return null

        val name = "BibliotecaIA-$tag.apk"
        val assetPath = "$OFFICIAL_REPO/releases/download/$tag/"
        val assets = json.optJSONArray("assets") ?: return null
        var apk: String? = null
        var sha: String? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val assetName = asset.optString("name")
            val url = asset.optString("browser_download_url")
            when {
                assetName == name && url == assetPath + name &&
                    asset.optLong("size") > 0 -> apk = url
                assetName == "$name.sha256" && url == assetPath + "$name.sha256" &&
                    asset.optLong("size") > 0 -> sha = url
            }
        }
        return if (apk != null && sha != null)
            ReleaseInfo(
                tagName = tag,
                versionName = tag.removePrefix("v"),
                notes = json.optString("body").take(600),
                htmlUrl = page,
                apkUrl = apk,
                sha256Url = sha,
            )
        else null
    }

    /**
     * Never blocks reading: network errors, rate limits, 404 (no published update)
     * or malformed release metadata just mean no result on this check.
     */
    fun latestRelease(currentVersion: String): ReleaseInfo? {
        return try {
        val connection = URL(RELEASES_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "BibliotecaIA-Android-UpdateChecker")
        try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.use { stream ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (output.size() <= MAX_RELEASE_BYTES) {
                    val read = stream.read(chunk)
                    if (read == -1) break
                    output.write(chunk, 0, read)
                }
                if (output.size() > MAX_RELEASE_BYTES) return null
                output.toString("UTF-8")
            }
            parseLatest(body, currentVersion)
        } finally {
            connection.disconnect()
        }
    } catch (_: Exception) {
        null
    }
    }
}
