package com.javierrb35.bibliotecaia.sync.coordinator.migration

/**
 * Read-only inventory reconciliation before linking or migrating a library.
 * No file creation, upload, deletion, cursor update or DB mutation is permitted here.
 * A verified original means bytes+SHA-256+size, never a filename or catalog entry.
 */
internal enum class OriginalEvidence { VERIFIED, NOT_CHECKED, MISSING, INVALID }

internal data class LocalBookCandidate(
    val sourceId: String,
    val bookId: String,
    val hash: String,
    val sizeBytes: Long,
    val evidence: OriginalEvidence
)

internal data class LocalDeletion(val sourceId: String, val hash: String)

internal data class CentralBookCandidate(
    val hash: String,
    val sizeBytes: Long,
    val revision: Long,
    val deleted: Boolean,
    val evidence: OriginalEvidence
)

internal enum class ReconciliationAction {
    KEEP_VERIFIED_CENTRAL, UPLOAD_ONE_VERIFIED_ORIGINAL,
    VERIFY_OR_REPAIR_CENTRAL, VERIFY_LOCAL_ORIGINAL,
    MISSING_OR_INVALID_ORIGINAL, DELETION_CONFLICT, PRESERVE_TOMBSTONE,
    REVIEW_INCONSISTENT_SIZE
}

/** All old book IDs are retained for later note/position remapping. No metadata
 * is silently selected or dropped based on device clocks or on the upload source. */
internal data class ReconciledBook(
    val hash: String,
    val action: ReconciliationAction,
    val local: List<LocalBookCandidate>,
    val localDeletions: List<LocalDeletion>,
    val central: CentralBookCandidate?,
    val uploadSource: LocalBookCandidate?
) {
    val requiresReview: Boolean get() = action !in setOf(
        ReconciliationAction.KEEP_VERIFIED_CENTRAL,
        ReconciliationAction.UPLOAD_ONE_VERIFIED_ORIGINAL,
        ReconciliationAction.PRESERVE_TOMBSTONE
    )
}

internal data class ReconciliationPlan(
    val libraryId: java.util.UUID,
    val observedHead: Long,
    val entries: List<ReconciledBook>
) {
    // Even a plan without unresolved originals does not authorize migration:
    // independent metadata conflict resolution, backup, current CAS and byte
    // verification must be completed by the execution layer.
    val unresolvedOriginals: Int get() = entries.count { it.requiresReview }
}

internal object MigrationPlanner {
    private val hashPattern = Regex("[a-f0-9]{64}")
    private fun checkHash(hash: String) = require(hashPattern.matches(hash)) { "Hash inválido" }

    fun plan(
        libraryId: java.util.UUID,
        observedHead: Long,
        local: List<LocalBookCandidate>,
        deletions: List<LocalDeletion>,
        central: List<CentralBookCandidate>
    ): ReconciliationPlan {
        require(observedHead >= 0)
        val sourceBookIds = mutableSetOf<Pair<String, String>>()
        local.forEach {
            checkHash(it.hash)
            require(it.sourceId.isNotBlank() && it.bookId.isNotBlank() && it.sizeBytes >= 0)
            require(sourceBookIds.add(it.sourceId to it.bookId)) { "Inventario local ambiguo" }
        }
        deletions.forEach { checkHash(it.hash); require(it.sourceId.isNotBlank()) }
        central.forEach {
            checkHash(it.hash)
            require(it.sizeBytes >= 0 && it.revision in 1..observedHead)
        }
        require(central.map { it.hash }.toSet().size == central.size) { "Inventario central ambiguo" }

        val localByHash = local.groupBy { it.hash }
        val deletionByHash = deletions.distinct().groupBy { it.hash }
        val centralByHash = central.associateBy { it.hash }
        val hashes = (localByHash.keys + deletionByHash.keys + centralByHash.keys).sorted()
        val entries = hashes.map { hash ->
            val candidates = localByHash[hash].orEmpty().sortedWith(
                compareBy<LocalBookCandidate> { it.sourceId }.thenBy { it.bookId }
            )
            val tombstones = deletionByHash[hash].orEmpty().sortedBy { it.sourceId }
            val remote = centralByHash[hash]
            val anyDeletion = remote?.deleted == true || tombstones.isNotEmpty()
            val anyLive = candidates.isNotEmpty() || (remote != null && !remote.deleted)
            val sizes = candidates.map { it.sizeBytes } + listOfNotNull(remote?.takeUnless { it.deleted }?.sizeBytes)
            val verified = candidates.firstOrNull { it.evidence == OriginalEvidence.VERIFIED }
            // Tombstones are never silently overridden by a surviving old copy.
            // A live central row with an absent/invalid physical original must be
            // repaired under a separate reservation; never create a second file.
            val action = when {
                anyDeletion && anyLive -> ReconciliationAction.DELETION_CONFLICT
                anyDeletion -> ReconciliationAction.PRESERVE_TOMBSTONE
                sizes.distinct().size > 1 -> ReconciliationAction.REVIEW_INCONSISTENT_SIZE
                remote?.evidence == OriginalEvidence.VERIFIED -> ReconciliationAction.KEEP_VERIFIED_CENTRAL
                remote != null -> ReconciliationAction.VERIFY_OR_REPAIR_CENTRAL
                verified != null -> ReconciliationAction.UPLOAD_ONE_VERIFIED_ORIGINAL
                candidates.any { it.evidence == OriginalEvidence.NOT_CHECKED } -> ReconciliationAction.VERIFY_LOCAL_ORIGINAL
                else -> ReconciliationAction.MISSING_OR_INVALID_ORIGINAL
            }
            ReconciledBook(
                hash, action, candidates, tombstones, remote,
                verified.takeIf { action == ReconciliationAction.UPLOAD_ONE_VERIFIED_ORIGINAL }
            )
        }
        return ReconciliationPlan(libraryId, observedHead, entries)
    }
}
