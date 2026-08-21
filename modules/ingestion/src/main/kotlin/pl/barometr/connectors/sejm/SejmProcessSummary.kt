package pl.barometr.connectors.sejm

import java.time.LocalDateTime

/**
 * What the index of legislative processes says about one of them.
 *
 * Deliberately not the process itself: the index carries neither the stages nor the
 * identifiers that make a process worth archiving, so treating an entry as the thing
 * would archive a hollow copy of it. It carries exactly what decides whether the real
 * one is worth fetching.
 */
data class SejmProcessSummary(
    val number: String,
    /** When the Sejm last touched the record. The only signal that a stage moved. */
    val changedAt: LocalDateTime?,
)
