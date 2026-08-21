package pl.barometr.connectors.sejm

import java.time.LocalDate

/**
 * A sitting, and the two things that might identify it.
 *
 * Most sittings are numbered, and their votings hang off that number. Eleven of term
 * 10's seventy-five are not: the National Assembly, ceremonial assemblies of deputies
 * and senators, and every sitting still only planned all arrive with `number: 0` —
 * the API's way of saying it has none yet.
 *
 * Treating that zero as a number cost twice. All eleven were archived under one
 * address, so the corpus derived them as eleven versions of a single document — a
 * revision history of eleven unrelated events, and provenance pointing at "version 7"
 * of nothing in particular. And a backfill chunk that ended inside the group lost the
 * rest of it for good, because resuming filters on `number > 0`.
 */
class SejmProceeding internal constructor(
    /** Null when the API has not numbered this sitting. Votings hang off a number only. */
    val number: Int?,
    /** The first day it sits — unique across the unnumbered ones, and what a person calls it by. */
    val firstDate: LocalDate?,
    val entity: SejmEntity,
)
