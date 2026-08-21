package pl.barometr.connectors.rcl.api

import java.time.LocalDate

/**
 * One stage of a draft's passage.
 *
 * Every stage has a [catalogId] from the moment the draft is created — the change
 * register shows all of them filed within three minutes of each other — so the id
 * is present even for stages nothing has happened in. [isVisitable] is the useful
 * distinction: it says whether RPL links the stage, and therefore whether there is
 * anything behind it to fetch.
 */
data class RclStage(
    val catalogId: String,
    /** The number RPL prints before the name; absent if the name is unnumbered. */
    val ordinal: Int?,
    val name: String,
    val state: RclStageState,
    val lastModifiedAt: LocalDate?,
    val isVisitable: Boolean,
)
