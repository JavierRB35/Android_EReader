package com.javierrb35.bibliotecaia.sync.coordinator.migration

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MigrationPlannerTest {
    private val library = UUID.randomUUID()
    private val h = "a".repeat(64)
    private val h2 = "b".repeat(64)
    private fun local(source: String, id: String, hash: String = h, size: Long = 9,
                      evidence: OriginalEvidence = OriginalEvidence.VERIFIED) =
        LocalBookCandidate(source, id, hash, size, evidence)

    private fun remote(hash: String = h, revision: Long = 2, deleted: Boolean = false,
                       evidence: OriginalEvidence = OriginalEvidence.VERIFIED, size: Long = 9) =
        CentralBookCandidate(hash, size, revision, deleted, evidence)

    @Test fun twoDevicesOneHashOneUploadPreservesAllBookIdentities() {
        val a = local("phone", "id1")
        val b = local("tablet", "id2")
        val p = MigrationPlanner.plan(library, 2, listOf(b,a), emptyList(), emptyList())
        val q = MigrationPlanner.plan(library, 2, listOf(a,b), emptyList(), emptyList())
        assertEquals(p, q)
        assertEquals(1, p.entries.size)
        assertEquals(0, p.unresolvedOriginals)
        assertEquals(ReconciliationAction.UPLOAD_ONE_VERIFIED_ORIGINAL, p.entries.single().action)
        assertEquals(a, p.entries.single().uploadSource)
        assertEquals(listOf(a,b), p.entries.single().local)
        assertEquals(library, p.libraryId)
        assertEquals(2L, p.observedHead)
    }

    @Test fun verifiedCentralReusedAndMissingCentralDoesNotBlindlyUpload() {
        val a = local("phone","id1")
        val keep = MigrationPlanner.plan(library, 2, listOf(a), emptyList(), listOf(remote())).entries.single()
        assertEquals(ReconciliationAction.KEEP_VERIFIED_CENTRAL, keep.action)
        assertNull(keep.uploadSource)
        val repair = MigrationPlanner.plan(library, 2, listOf(a), emptyList(),
            listOf(remote(evidence=OriginalEvidence.MISSING))).entries.single()
        assertEquals(ReconciliationAction.VERIFY_OR_REPAIR_CENTRAL, repair.action)
        assertNull(repair.uploadSource)
        assertTrue(repair.requiresReview)
    }

    @Test fun tombstonesPreventResurrectionOrAutomaticDeletion() {
        val a = local("phone","id1")
        val remoteDeleted = remote(deleted=true, evidence=OriginalEvidence.MISSING)
        val stale = MigrationPlanner.plan(library, 2, listOf(a), emptyList(), listOf(remoteDeleted)).entries.single()
        assertEquals(ReconciliationAction.DELETION_CONFLICT, stale.action)
        assertNull(stale.uploadSource)
        assertEquals(a, stale.local.single())
        val localDeleted = MigrationPlanner.plan(library, 2, emptyList(), listOf(LocalDeletion("phone",h)),
            listOf(remote())).entries.single()
        assertEquals(ReconciliationAction.DELETION_CONFLICT, localDeleted.action)
        val deleted = MigrationPlanner.plan(library, 2, emptyList(), listOf(LocalDeletion("phone",h)),
            listOf(remoteDeleted)).entries.single()
        assertEquals(ReconciliationAction.PRESERVE_TOMBSTONE, deleted.action)
        assertNull(deleted.uploadSource)
    }

    @Test fun unverifiedLocalAndInconsistentSizesNeverAuthorizeUpload() {
        val missing = local("phone","id1",evidence=OriginalEvidence.MISSING)
        val unchecked = local("tablet","id2",evidence=OriginalEvidence.NOT_CHECKED)
        val p = MigrationPlanner.plan(library, 0, listOf(missing,unchecked), emptyList(), emptyList())
        assertEquals(ReconciliationAction.VERIFY_LOCAL_ORIGINAL,p.entries.single().action)
        assertEquals(ReconciliationAction.MISSING_OR_INVALID_ORIGINAL,
            MigrationPlanner.plan(library,0,listOf(missing),emptyList(),emptyList()).entries.single().action)
        val wrongSize = local("tablet","id2",size=10)
        val inconsistent = MigrationPlanner.plan(library,0,listOf(missing,wrongSize),emptyList(),emptyList()).entries.single()
        assertEquals(ReconciliationAction.REVIEW_INCONSISTENT_SIZE,inconsistent.action)
        assertNull(inconsistent.uploadSource)
    }

    @Test fun invalidInventoryIsRejectedRatherThanDiscardingRows() {
        assertThrows(IllegalArgumentException::class.java) {
            MigrationPlanner.plan(library,2,listOf(local("phone","id1"),local("phone","id1",h2)),emptyList(),emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            MigrationPlanner.plan(library,2,emptyList(),emptyList(),listOf(remote(),remote()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MigrationPlanner.plan(library,2,emptyList(),emptyList(),listOf(remote(revision=3)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            MigrationPlanner.plan(library,0,listOf(local("phone","id1","invalid")),emptyList(),emptyList())
        }
    }
}
