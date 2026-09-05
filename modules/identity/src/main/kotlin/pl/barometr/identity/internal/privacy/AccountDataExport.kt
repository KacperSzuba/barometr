package pl.barometr.identity.internal.privacy

import java.time.Instant
import java.util.UUID

/**
 * A request for a copy of everything, and what became of it.
 *
 * [expiresAt] is a privacy decision rather than housekeeping: an export is the most
 * concentrated collection of somebody's data this system ever produces, and leaving it
 * behind a URL for ever would mean exercising a right quietly made the data easier to
 * take.
 */
data class AccountDataExport(
    val id: UUID,
    val user: UUID,
    val status: ExportStatus,
    val byteSize: Long?,
    val detail: String?,
    val requestedAt: Instant,
    val completedAt: Instant?,
    val expiresAt: Instant,
)
