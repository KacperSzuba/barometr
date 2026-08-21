package pl.barometr.alerts.internal

import java.time.Instant
import java.util.UUID

/**
 * One closed window.
 *
 * Carries the moment and nothing else. Who it is for is how it was asked for, what is
 * in it is the notifications pointing at it, and what it covers is their own
 * timestamps — a window recorded twice, here and in its contents, is a window that can
 * disagree with itself.
 */
data class Digest(val id: UUID, val createdAt: Instant)
