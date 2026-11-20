package com.javierrb35.bibliotecaia

import com.javierrb35.bibliotecaia.data.BookEntity
import com.javierrb35.bibliotecaia.data.BookFormat
import com.javierrb35.bibliotecaia.data.ReadingStatus
import com.javierrb35.bibliotecaia.data.SyncRows
import com.javierrb35.bibliotecaia.sync.SyncPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncPolicyAndRowsTest {

    private fun book() = BookEntity(
        id = "b-1", title = "El jardín de las ideas", authors = null,
        format = BookFormat.EPUB, fileName = "sample.epub", localPath = "/solo/local.epub",
        contentHash = "hash-1", sizeBytes = 2048, addedAt = 1_700_000_000_000,
        readingStatus = ReadingStatus.PENDING, progress = 0.5f,
    )

    @Test
    fun `fila remota de libro sin campos de dispositivo`() {
        val row = SyncRows.bookJson(book())
        assertTrue(row.has("id"))
        assertTrue(row.has("content_hash"))
        assertFalse(row.has("localPath"))
        assertFalse(row.has("local_path"))
        assertEquals(0.5, row.getDouble("progress"), 0.0001)
    }

    @Test
    fun `posiciones — sin sobrescritura silenciosa`() {
        assertEquals(SyncPolicy.Winner.REMOTE, SyncPolicy.resolvePosition(100, 200))
        assertEquals(SyncPolicy.Winner.LOCAL, SyncPolicy.resolvePosition(200, 100))
        assertEquals(SyncPolicy.Winner.LOCAL, SyncPolicy.resolvePosition(200, 200))
    }

    @Test
    fun `anotaciones remotas — solo altas nuevas, nunca sobreescritura`() {
        // Nueva en remoto, no borrada: se aplica
        assertTrue(SyncPolicy.applyRemoteAnnotation(existsLocally = false, remoteDeleted = false))
        // Nueva en remoto pero ya borrada (lápida): no se crea
        assertFalse(SyncPolicy.applyRemoteAnnotation(existsLocally = false, remoteDeleted = true))
        // Ya existe: jamás se sobreescribe el texto local
        assertFalse(SyncPolicy.applyRemoteAnnotation(existsLocally = true, remoteDeleted = false))
        assertFalse(SyncPolicy.applyRemoteAnnotation(existsLocally = true, remoteDeleted = true))
    }

    @Test
    fun `lapidado remoto solo afecta a filas existentes`() {
        assertTrue(SyncPolicy.shouldTombstoneLocal(existsLocally = true, remoteDeleted = true))
        assertFalse(SyncPolicy.shouldTombstoneLocal(existsLocally = false, remoteDeleted = true))
        assertFalse(SyncPolicy.shouldTombstoneLocal(existsLocally = true, remoteDeleted = false))
    }

    @Test
    fun `pull filtra filas ya conocidas por id`() {
        val remote = listOf(
            JSONObject().put("id", "b-1"),
            JSONObject().put("id", "b-2"),
            JSONObject().put("id", ""), // fila sin id: descartada
        )
        val unknown = SyncPolicy.unknownIds(localIds = setOf("b-1"), remoteRows = remote, idKey = "id")
        assertEquals(1, unknown.size)
        assertEquals("b-2", unknown.single().getString("id"))
    }
}
