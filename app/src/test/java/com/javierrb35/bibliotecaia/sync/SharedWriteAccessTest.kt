package com.javierrb35.bibliotecaia.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedWriteAccessTest {
    @Test fun connectedWritableLibraryIsReadyWithoutIndependentBackup() {
        assertEquals(SharedWriteAccess.READY, SharedWriteAccess.evaluate(true, true, false))
    }

    @Test fun missingWriteGrantOrIdentityMismatchBlocksImport() {
        assertEquals(SharedWriteAccess.NOT_CONNECTED, SharedWriteAccess.evaluate(false, false, false))
        assertEquals(SharedWriteAccess.NO_WRITE_GRANT, SharedWriteAccess.evaluate(true, false, false))
        assertEquals(SharedWriteAccess.NEEDS_REPAIR, SharedWriteAccess.evaluate(true, true, true))
    }
}
