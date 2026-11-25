package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.updates.UpdateChecker
import com.javierrb35.bibliotecaia.updates.UpdateNotes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerVersionTest {

    private fun officialRelease(
        tag: String = "v0.1.22",
        draft: Boolean = false,
        prerelease: Boolean = false,
        apkUrl: String? = null,
        withChecksum: Boolean = true,
    ): String {
        val base = "https://github.com/JavierRB35/Android_EReader/releases"
        val asset = "BibliotecaIA-$tag.apk"
        val assets = org.json.JSONArray().put(
            org.json.JSONObject()
                .put("name", asset)
                .put("browser_download_url", apkUrl ?: "$base/download/$tag/$asset")
                .put("size", 15120000)
        )
        if (withChecksum) assets.put(
            org.json.JSONObject()
                .put("name", "$asset.sha256")
                .put("browser_download_url", "$base/download/$tag/$asset.sha256")
                .put("size", 100)
        )
        return org.json.JSONObject()
            .put("draft", draft)
            .put("prerelease", prerelease)
            .put("tag_name", tag)
            .put("html_url", "$base/tag/$tag")
            .put("body", "Notas de actualización verificadas")
            .put("assets", assets)
            .toString()
    }

    @Test
    fun `acepta una release oficial mas reciente con APK y sha256`() {
        val release = requireNotNull(UpdateChecker.parseLatest(officialRelease(), "0.1.0"))
        org.junit.Assert.assertEquals("v0.1.22", release.tagName)
        org.junit.Assert.assertEquals("0.1.22", release.versionName)
        assertTrue(release.apkUrl.endsWith("BibliotecaIA-v0.1.22.apk"))
        assertTrue(release.sha256Url.endsWith(".apk.sha256"))
    }

    @Test
    fun `no propone misma version ni anterior`() {
        org.junit.Assert.assertNull(UpdateChecker.parseLatest(officialRelease(), "0.1.22"))
        org.junit.Assert.assertNull(UpdateChecker.parseLatest(officialRelease(), "0.1.30"))
    }

    @Test
    fun `rechaza versiones beta y releases en borrador`() {
        org.junit.Assert.assertNull(UpdateChecker.parseLatest(
            officialRelease(draft = true), "0.1.0"))
        org.junit.Assert.assertNull(UpdateChecker.parseLatest(
            officialRelease(prerelease = true), "0.1.0"))
        org.junit.Assert.assertNull(UpdateChecker.parseLatest(
            officialRelease(tag = "v0.1.22-beta"), "0.1.0"))
    }

    @Test
    fun `no anuncia APK ausente o sin checksum oficial`() {
        org.junit.Assert.assertNull(UpdateChecker.parseLatest(
            officialRelease(withChecksum = false), "0.1.0"))
        org.junit.Assert.assertNull(UpdateChecker.parseLatest(
            officialRelease(apkUrl = "https://example.com/fake.apk"), "0.1.0"))
    }

    @Test
    fun `rechaza pagina que suplante GitHub Releases`() {
        val json = org.json.JSONObject(officialRelease())
            .put("html_url", "https://example.com/release/v0.1.22")
        org.junit.Assert.assertNull(UpdateChecker.parseLatest(json.toString(), "0.1.0"))
    }

    @Test
    fun `detecta versiones mayores y menores`() {
        assertTrue(UpdateChecker.isNewer("0.1.0", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("0.1.0", "1.0.0"))
        assertTrue(UpdateChecker.isNewer("0.1.9", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("0.1", "0.1.1"))
    }

    @Test
    fun `la misma version o inferior no actualiza`() {
        assertFalse(UpdateChecker.isNewer("0.1.0", "0.1.0"))
        assertFalse(UpdateChecker.isNewer("0.2.0", "0.1.9"))
        assertFalse(UpdateChecker.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `tolera prefijo v y segmentos ausentes`() {
        assertTrue(UpdateChecker.isNewer("0.1.0", "v0.2.0"))
        assertTrue(UpdateChecker.isNewer("v0.1.0", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("0.1", "0.1"))
    }

    @Test
    fun `segmentos no numericos cuentan como cero`() {
        assertTrue(UpdateChecker.isNewer("0.1.x", "0.1.1"))
        assertFalse(UpdateChecker.isNewer("0.1.x", "0.1"))
    }
    @Test
    fun `resumen de cambios visible y metadatos tecnicos excluidos`() {
        val body = "## Cambios de esta versión\n- Acceso directo a Drive\n- Importación múltiple\n\nCompilación del commit abc"
        val release = requireNotNull(UpdateChecker.parseLatest(
            org.json.JSONObject(officialRelease()).put("body", body).toString(), "0.1.0"))
        org.junit.Assert.assertEquals("Cambios de esta versión\n• Acceso directo a Drive\n• Importación múltiple", release.notes)
        org.junit.Assert.assertEquals("Acceso directo a Drive", UpdateNotes.preview(release.notes))
    }

    @Test
    fun `version antigua con texto generico no inventa cambios`() {
        val summary = UpdateNotes.fromReleaseBody("Actualización Android firmada y comprobada. Compilación del commit abc. Serie de desarrollo; instalación voluntaria desde la aplicación.")
        assertTrue(summary.isBlank())
        assertTrue(UpdateNotes.detail(summary).contains("No se ha publicado"))
        assertTrue(UpdateNotes.preview(summary).contains("No se ha publicado"))
    }

    @Test
    fun `resumen largo se limita sin eliminar la primera mejora`() {
        val text = UpdateNotes.fromReleaseBody("## Cambios\n- Drive\n" + "a".repeat(6000))
        assertTrue(text.length <= 4000)
        org.junit.Assert.assertEquals("Drive", UpdateNotes.preview(text))
    }
}
