package pl.barometr.legislative.api

import java.time.Instant

/**
 * Published when an act has been recorded or restated from the register.
 *
 * Thin on purpose: it says which act changed and nothing about it. Whoever cares reads
 * the act through [LegislativeCatalog], which keeps one description of an act in one
 * place instead of a copy of it in every event ever published.
 */
data class ActRecorded(val actId: ActId, val occurredAt: Instant)
