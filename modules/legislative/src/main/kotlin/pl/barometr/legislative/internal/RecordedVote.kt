package pl.barometr.legislative.internal

import java.time.Instant

/**
 * A vote as a card shows it: when it was taken, what it was about, and the numbers.
 *
 * Separate from [SejmVoteRecord], which is what was read out of the archive — a card
 * shows a stored vote, and the prints it cited are how it was found rather than
 * something to repeat back to the reader who asked about one of them.
 */
data class RecordedVote(
    val term: Int,
    val sitting: Int,
    val votingNumber: Int,
    val takenAt: Instant,
    val title: String,
    val subject: String?,
    val method: String,
    val majority: String?,
    val yes: Int,
    val no: Int,
    val abstained: Int,
    val notParticipating: Int,
    val totalVoted: Int,
)
