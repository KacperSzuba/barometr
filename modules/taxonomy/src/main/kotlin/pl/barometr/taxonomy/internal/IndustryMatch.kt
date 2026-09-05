package pl.barometr.taxonomy.internal

import pl.barometr.shared.PkdCode

/**
 * One industry a title pointed at, how sure that makes this, and what said so.
 *
 * [reasons] is not decoration: a verdict below the acceptance threshold goes to a
 * person, and "why does this look like construction" is the whole of what they need to
 * decide it in a second rather than a minute.
 */
data class IndustryMatch(
    val code: PkdCode,
    val confidence: Double,
    val reasons: List<String>,
)
