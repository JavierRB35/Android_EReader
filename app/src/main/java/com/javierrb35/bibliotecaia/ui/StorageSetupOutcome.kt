package com.javierrb35.bibliotecaia.ui

/** A configuration never finishes with an undiagnosed collection of pending flags. */
internal object StorageSetupOutcome {
    enum class Issue { LIBRARY_ACCESS, LIBRARY_OWNER, LIBRARY_SYNC, BACKUP_RECOVERY,
        BACKUP_ACCESS, BACKUP_MISSING, BACKUP_WRITE, PROGRESS_WRITE, OTHER_DEVICE, PROGRESS_READ, NONE }
    data class State(val libraryAccess: Boolean, val libraryOwner: Boolean, val libraryOk: Boolean,
        val backupRecovery: Boolean, val backupAccess: Boolean, val missingBooks: Boolean,
        val backupOk: Boolean, val progressWritten: Boolean, val otherDevice: Boolean, val progressRead: Boolean)
    fun first(s: State): Issue = when {
        !s.libraryAccess -> Issue.LIBRARY_ACCESS
        !s.libraryOwner -> Issue.LIBRARY_OWNER
        !s.libraryOk -> Issue.LIBRARY_SYNC
        s.backupRecovery -> Issue.BACKUP_RECOVERY
        !s.backupAccess -> Issue.BACKUP_ACCESS
        s.missingBooks -> Issue.BACKUP_MISSING
        !s.backupOk -> Issue.BACKUP_WRITE
        !s.progressWritten -> Issue.PROGRESS_WRITE
        !s.otherDevice -> Issue.OTHER_DEVICE
        !s.progressRead -> Issue.PROGRESS_READ
        else -> Issue.NONE
    }
}
