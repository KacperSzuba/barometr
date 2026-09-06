package pl.barometr.legislative.internal

import java.time.Instant

/**
 * One voting of the Sejm, as the register stated it.
 *
 * Every field is the source's, and there is deliberately no verdict among them: whether
 * a vote carried is not something the register says, and computing it here from
 * [majority] and the counts would be this system's arithmetic presented as the Sejm's
 * word. A reader is shown the numbers and the rule they were counted under.
 */
data class SejmVoteRecord(
    val term: Int,
    val sitting: Int,
    val votingNumber: Int,
    /** Which day of a sitting that runs over several; null where the register omits it. */
    val sittingDay: Int?,
    val takenAt: Instant,
    /** The agenda item, which is the only place the prints under debate are named. */
    val title: String,
    /** What this particular vote decided, where the register distinguishes it from the item. */
    val subject: String?,
    /** `ELECTRONIC`, `ON_LIST`, `TRADITIONAL` — the register's own word, unmapped. */
    val method: String,
    val majority: String?,
    val majorityVotes: Int?,
    val yes: Int,
    val no: Int,
    val abstained: Int,
    val notParticipating: Int,
    val totalVoted: Int,
    /** Addresses of the prints [title] cites; empty for a vote that concerns no bill. */
    val citedPrints: List<String>,
) {
    init {
        require(term > 0) { "A term is numbered from one, got $term" }
        require(title.isNotBlank()) { "A vote without an agenda item states nothing" }
    }
}
