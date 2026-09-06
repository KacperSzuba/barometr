package pl.barometr.legislative.internal

import java.util.UUID

/**
 * This context's identity for one voting of the Sejm.
 *
 * Internal, unlike [pl.barometr.legislative.api.DraftId]: nothing outside legislative
 * addresses a vote yet, and an identifier published before anybody needs it is a
 * promise made for nothing.
 */
@JvmInline
value class VoteId(val value: UUID) {
    override fun toString(): String = value.toString()
}
