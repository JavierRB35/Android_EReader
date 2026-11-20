package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.updates.UpdateChecker
import com.javierrb35.bibliotecaia.updates.UpdatePayloadPolicy
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class UpdatePayloadPolicyTest {
    private val name = "BibliotecaIA-v0.1.4.apk"
    private val base = "https://github.com/JavierRB35/Android_EReader/releases/download/v0.1.4/"

    private fun release(apk: String = base + name, sha: String = base + name + ".sha256") =
        UpdateChecker.ReleaseInfo("v0.1.4", "0.1.4", "", "", apk, sha)

    @Test fun acceptsOnlyMatchingOfficialReleaseAssetAndChecksumUrls() {
        assertEquals(name, UpdatePayloadPolicy.assetName(release()))
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePayloadPolicy.assetName(release(apk = "https://evil.example/attack.apk"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePayloadPolicy.assetName(release(sha = "https://evil.example/fake.sha256"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePayloadPolicy.assetName(UpdateChecker.ReleaseInfo(
                "v0.1.4", "0.1.5", "", "", base + name, base + name + ".sha256"))
        }
    }

    @Test fun acceptsOnlyChecksumBoundToExpectedApk() {
        val hash = "ab".repeat(32)
        assertEquals(hash, UpdatePayloadPolicy.parseChecksum(
            hash.uppercase() + "  " + name + "\n", name))
        listOf(
            hash + "  another.apk\n",
            hash + "  ../../" + name + "\n",
            hash + "  " + name + "\nsecond line\n",
            hash + "\n",
            "xxxx  " + name,
            hash + "  " + name + ".zip"
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                UpdatePayloadPolicy.parseChecksum(it, name)
            }
        }
    }

    @Test fun verifiesHashAndRejectsTampering() {
        val content = "APK of trusted release".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(content)
        val hex = hash.joinToString("") { "%02x".format(it.toInt() and 255) }
        assertTrue(UpdatePayloadPolicy.isExpectedHash(hash, hex))
        assertFalse(UpdatePayloadPolicy.isExpectedHash(
            MessageDigest.getInstance("SHA-256").digest("APK altered".toByteArray()), hex))
        assertFalse(UpdatePayloadPolicy.isExpectedHash(hash, "bad-sha"))
    }

    @Test fun onlySamePackageHigherVersionAndSameCertificateCanUpgrade() {
        val official = setOf("a1b2c3")
        val packageName = "com.javierrb35.bibliotecaia"
        UpdatePayloadPolicy.requireSignedUpgrade(packageName, packageName,
            100004, 100005, official, official)
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePayloadPolicy.requireSignedUpgrade(packageName, "org.other.reader",
                100004, 100005, official, official)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePayloadPolicy.requireSignedUpgrade(packageName, packageName,
                100004, 100004, official, official)
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePayloadPolicy.requireSignedUpgrade(packageName, packageName,
                100004, 100006, official, setOf("unauthorized"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            UpdatePayloadPolicy.requireSignedUpgrade(packageName, packageName,
                100004, 100006, emptySet(), emptySet())
        }
    }

    @Test fun restrictsCdnRedirectsToOfficialHttpsHostnames() {
        assertTrue(UpdatePayloadPolicy.allowedDownloadHost("github.com"))
        assertTrue(UpdatePayloadPolicy.allowedDownloadHost("release-assets.githubusercontent.com"))
        assertFalse(UpdatePayloadPolicy.allowedDownloadHost("github.com.evil.example"))
        assertFalse(UpdatePayloadPolicy.allowedDownloadHost("evil.example"))
        assertFalse(UpdatePayloadPolicy.allowedDownloadHost("localhost"))
    }
}
