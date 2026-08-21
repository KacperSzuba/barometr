package pl.barometr.legislative.api

import java.time.LocalDate

/**
 * A draft as other contexts see it, including where it currently stands.
 *
 * [currentStage] comes from the status read model rather than from the history, so it
 * is as fresh as the last rebuild — which is what a facet on a search result needs and
 * more than a card does. [identifiers] carries the numbers people actually quote a
 * draft by, so that searching for `UD383` or a print number finds it.
 */
data class TrackedDraft(
    val id: DraftId,
    val title: String,
    val initiator: String,
    val term: Int?,
    val startedOn: LocalDate?,
    val closedOn: LocalDate?,
    val outcome: String?,
    val currentStage: String?,
    val identifiers: List<String>,
)
