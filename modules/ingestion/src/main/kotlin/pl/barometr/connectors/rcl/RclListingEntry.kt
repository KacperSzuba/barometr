package pl.barometr.connectors.rcl

import java.time.LocalDate

/** One row of a draft index: enough to decide whether the draft is worth visiting. */
data class RclListingEntry(
    val projectId: String,
    val title: String,
    val applicant: String,
    /**
     * The draft's number in its ministry's programme of work — `UD412`, `RD319`,
     * `MZ1921`. Null for drafts filed outside any programme.
     */
    val registerNumber: String?,
    val createdAt: LocalDate?,
    val modifiedAt: LocalDate?,
)
