package com.javierrb35.bibliotecaia.updates

import java.security.MessageDigest
import java.util.Locale

/** Pure constraints: only the official GitHub release can be installed. */
internal object UpdatePayloadPolicy {
    private const val OFFICIAL = "https://github.com/JavierRB35/Android_EReader/releases/download/"
    private val TAG = Regex("v[0-9]+\\.[0-9]+\\.[0-9]+")

    fun assetName(release: UpdateChecker.ReleaseInfo): String {
        require(TAG.matches(release.tagName) &&
            release.versionName == release.tagName.removePrefix("v")) {
            "La versión de la actualización no es válida."
        }
        val name = "BibliotecaIA-" + release.tagName + ".apk"
        val exact = OFFICIAL + release.tagName + "/" + name
        require(release.apkUrl == exact && release.sha256Url == exact + ".sha256") {
            "La actualización no procede del repositorio oficial."
        }
        return name
    }

    /** Accept the exact sha256sum output, bound to the actual APK name. */
    fun parseChecksum(sha256sum: String, expectedFilename: String): String {
        val match = Regex("(?i)^([a-f0-9]{64})  \\*?([A-Za-z0-9._-]+)\\r?\\n?$")
            .matchEntire(sha256sum)
            ?: throw IllegalArgumentException("El manifiesto SHA-256 no es válido.")
        require(match.groupValues[2] == expectedFilename) {
            "El SHA-256 no corresponde a esta APK."
        }
        return match.groupValues[1].lowercase(Locale.ROOT)
    }

    fun isExpectedHash(actualBytes: ByteArray, expectedHex: String): Boolean {
        if (!Regex("[a-f0-9]{64}").matches(expectedHex)) return false
        val expectedBytes = expectedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.isEqual(actualBytes, expectedBytes)
    }

    fun requireSignedUpgrade(
        installedPackage: String,
        incomingPackage: String,
        installedVersionCode: Long,
        incomingVersionCode: Long,
        installedSignatures: Set<String>,
        incomingSignatures: Set<String>
    ) {
        require(incomingPackage == installedPackage) {
            "La APK no pertenece a Biblioteca IA."
        }
        require(incomingVersionCode > installedVersionCode) {
            "La APK no es posterior a la instalada."
        }
        require(installedSignatures.isNotEmpty() && installedSignatures == incomingSignatures) {
            "La firma de esta APK no coincide con la aplicación instalada. No la desinstales."
        }
    }

    /** Official release CDN redirects; never accept HTTP or a foreign host. */
    fun allowedDownloadHost(host: String): Boolean = host in setOf(
        "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"
    )
}
