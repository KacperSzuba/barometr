package pl.barometr.alerts.internal

import java.util.UUID

/**
 * Something that moved, written down before anybody decides who should hear about it.
 *
 * Carries the identity and nothing else: what the act or draft now says is read back
 * when the batch runs, so a row that sat in the buffer for a minute does not describe
 * the world as it was a minute ago.
 */
data class PendingItem(
    val id: UUID,
    val kind: String,
    val subjectId: String,
)
