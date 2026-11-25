package com.javierrb35.bibliotecaia.backup

import com.javierrb35.bibliotecaia.data.AnnotationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationConflictResolutionTest {

    private fun note(body: String, updatedAt: Long, color: Int = 1, deletedAt: Long? = null) =
        AnnotationEntity(
            id = "n-1", bookId = "b-1", locator = "c:1", quote = "cita",
            body = body, kind = "NOTE", color = color,
            createdAt = 1L, updatedAt = updatedAt, deletedAt = deletedAt,
        )

    @Test
    fun `newer incoming edit wins and preserves existing text`() {
        val local = note("Edición A", updatedAt = 100L, color = 1)
        val remote = note("Edición B", updatedAt = 200L, color = 2)
        val res = BackupManager.resolveAnnotationConflict(local, remote)

        assertTrue(res.applyIncoming)
        assertNotNull(res.preservedVersion)
        assertEquals("Edición A", res.preservedVersion!!.body)
        assertEquals(1, res.preservedVersion!!.color)
        assertEquals(100L, res.preservedVersion!!.savedAt)
    }

    @Test
    fun `older incoming edit keeps local winner and still preserves incoming text`() {
        val local = note("Edición B", updatedAt = 200L, color = 2)
        val remote = note("Edición A", updatedAt = 100L, color = 1)
        val res = BackupManager.resolveAnnotationConflict(local, remote)

        assertFalse(res.applyIncoming)
        assertNotNull(res.preservedVersion)
        assertEquals("Edición A", res.preservedVersion!!.body)
        assertEquals(1, res.preservedVersion!!.color)
        assertEquals(100L, res.preservedVersion!!.savedAt)
        // Same deterministic conflict version ID regardless of merge direction.
        val reverse = BackupManager.resolveAnnotationConflict(remote, local)
        assertEquals(reverse.preservedVersion!!.id, res.preservedVersion!!.id)
    }

    @Test
    fun `same timestamp edits converge deterministically across both devices`() {
        val a = note("Alfa", updatedAt = 500L)
        val b = note("Beta", updatedAt = 500L)
        val aPullsB = BackupManager.resolveAnnotationConflict(a, b)
        val bPullsA = BackupManager.resolveAnnotationConflict(b, a)

        assertTrue(aPullsB.applyIncoming)
        assertFalse(bPullsA.applyIncoming)
        assertEquals("Alfa", aPullsB.preservedVersion!!.body)
        assertEquals("Alfa", bPullsA.preservedVersion!!.body)
        assertEquals(aPullsB.preservedVersion!!.id, bPullsA.preservedVersion!!.id)
    }

    @Test
    fun `identical content with only tombstone change creates no extra version`() {
        val local = note("Mismo texto", updatedAt = 100L, deletedAt = null)
        val remote = note("Mismo texto", updatedAt = 100L, deletedAt = 100L)
        val res = BackupManager.resolveAnnotationConflict(local, remote)

        assertTrue(res.applyIncoming)
        assertNull(res.preservedVersion)
    }
}
