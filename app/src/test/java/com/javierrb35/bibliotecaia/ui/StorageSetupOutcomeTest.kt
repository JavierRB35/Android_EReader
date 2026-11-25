package com.javierrb35.bibliotecaia.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageSetupOutcomeTest {
    private val good = StorageSetupOutcome.State(true, true, true, false, true, false, true, true, true, true)
    @Test fun oneFailureAtATime() {
        assertEquals(StorageSetupOutcome.Issue.LIBRARY_SYNC,
            StorageSetupOutcome.first(good.copy(libraryOk = false, backupOk = false, progressRead = false)))
        assertEquals(StorageSetupOutcome.Issue.BACKUP_MISSING,
            StorageSetupOutcome.first(good.copy(missingBooks = true, backupOk = false)))
        assertEquals(StorageSetupOutcome.Issue.OTHER_DEVICE,
            StorageSetupOutcome.first(good.copy(otherDevice = false)))
        assertEquals(StorageSetupOutcome.Issue.PROGRESS_READ,
            StorageSetupOutcome.first(good.copy(progressRead = false)))
        assertEquals(StorageSetupOutcome.Issue.PROGRESS_WRITE,
            StorageSetupOutcome.first(good.copy(progressWritten = false, otherDevice = false, progressRead = false)))
        assertEquals(StorageSetupOutcome.Issue.BACKUP_RECOVERY,
            StorageSetupOutcome.first(good.copy(backupRecovery = true, backupAccess = false)))
        assertEquals(StorageSetupOutcome.Issue.NONE, StorageSetupOutcome.first(good))
    }
}
