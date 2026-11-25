package com.javierrb35.bibliotecaia.ui

/** The wizard derives the next step from actual saved state, not a counter. */
internal object StorageSetupPlan {
    enum class Step { LIBRARY, BACKUP, POSITION, LIBRARY_PEER, POSITION_PEER, VERIFY }
    data class State(val library: Boolean, val backup: Boolean, val position: Boolean,
        val severalDevices: Boolean, val libraryPeer: Boolean, val positionPeer: Boolean)
    fun next(s: State): Step = when {
        !s.library -> Step.LIBRARY
        !s.backup -> Step.BACKUP
        !s.severalDevices -> Step.VERIFY
        !s.position -> Step.POSITION
        !s.libraryPeer -> Step.LIBRARY_PEER
        !s.positionPeer -> Step.POSITION_PEER
        else -> Step.VERIFY
    }
}
