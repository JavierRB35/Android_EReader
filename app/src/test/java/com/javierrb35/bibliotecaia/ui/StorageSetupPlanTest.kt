package com.javierrb35.bibliotecaia.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSetupPlanTest {
    @Test fun existingLibraryAndBackupAreSkipped() {
        val s = StorageSetupPlan.State(true, true, false, true, true, false)
        assertEquals(StorageSetupPlan.Step.POSITION, StorageSetupPlan.next(s))
        assertEquals(StorageSetupPlan.Step.POSITION_PEER, StorageSetupPlan.next(s.copy(position = true)))
        assertEquals(StorageSetupPlan.Step.VERIFY, StorageSetupPlan.next(s.copy(position = true, positionPeer = true)))
    }
    @Test fun singleDeviceDoesNotRequirePeerFiles() {
        val s = StorageSetupPlan.State(false, false, false, false, false, false)
        assertEquals(StorageSetupPlan.Step.BACKUP, StorageSetupPlan.next(s))
        assertEquals(StorageSetupPlan.Step.VERIFY, StorageSetupPlan.next(s.copy(backup = true)))
    }
}
