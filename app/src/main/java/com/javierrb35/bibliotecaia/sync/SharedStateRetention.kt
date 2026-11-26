package com.javierrb35.bibliotecaia.sync

import java.util.UUID

/** UUID v8 marks snapshots managed by this retention policy. Historic v4
 * publications remain readable and are never candidates for automatic removal. */
internal object SharedStateRetention {
    const val KEEP = 3
    private val pattern = Regex("bia-state-([a-f0-9]{32})-([0-9]{1,19})-([a-f0-9]{64})-([a-f0-9]{8}-[a-f0-9]{4}-8[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12})\\.json")
    data class State(val owner: String, val sequence: Long, val hash: String)
    fun parse(name: String): State? {
        val m = pattern.matchEntire(name) ?: return null
        val sequence = m.groupValues[2].toLongOrNull() ?: return null
        if (sequence <= 0) return null
        return State(m.groupValues[1], sequence, m.groupValues[3])
    }
    fun nonce(): String = UUID.randomUUID().toString().replaceRange(14, 15, "8")
}
