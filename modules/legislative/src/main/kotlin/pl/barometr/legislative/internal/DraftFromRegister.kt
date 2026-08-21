package pl.barometr.legislative.internal

import java.time.LocalDate

/**
 * A draft as a register describes it, in the words both registers can be said in.
 *
 * Neither the Sejm's process record nor RPL's card reaches the repository: they
 * describe the same thing in different vocabularies, and a repository that took
 * either would have to learn both. What survives the translation is what a draft
 * actually is.
 */
data class DraftFromRegister(
    val title: String,
    val initiator: DraftInitiator,
    /** Null on an RPL card that does not say which term it belongs to. */
    val term: Int?,
    val startedOn: LocalDate?,
    /** RPL never states one: its cards describe drafts that are still moving. */
    val closedOn: LocalDate? = null,
    val outcome: DraftOutcome? = null,
)
