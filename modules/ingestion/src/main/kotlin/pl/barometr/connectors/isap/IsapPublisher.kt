package pl.barometr.connectors.isap

/**
 * A journal the ELI API publishes: `DU` (Dziennik Ustaw) or `MP` (Monitor Polski).
 *
 * [years] comes from the API rather than from a range we assume. Dziennik Ustaw
 * starts in 1918 and Monitor Polski in 1930, with wartime gaps in both; a backfill
 * that generated the years itself would spend its first requests proving that 1925
 * of Monitor Polski does not exist.
 */
data class IsapPublisher(
    val code: String,
    val name: String,
    val years: List<Int>,
)
