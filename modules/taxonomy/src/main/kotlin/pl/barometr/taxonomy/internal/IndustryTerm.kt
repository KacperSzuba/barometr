package pl.barometr.taxonomy.internal

import pl.barometr.shared.PkdCode

/**
 * One thing a law can say that points at an industry, and how much it is worth.
 *
 * A term is a run of *stems*, not words: `transporcie drogowym` is written
 * `transporcie drogow` so that `drogowego` and `drogowym` both match, and matching is a
 * prefix comparison word by word. Writing whole words instead would mean a lexicon that
 * has to list every Polish case ending, which is how a lexicon stops being maintained.
 *
 * The weight is what a single occurrence of this term is worth as evidence, between
 * nothing and certainty. Specific phrases are written high — `odnawialnych zrodlach
 * energii` is what the law is about — and lone stems low, because `energetyczn` appears
 * in a title about energy prices and in one about a building's insulation.
 */
data class IndustryTerm(val code: PkdCode, val stems: List<String>, val weight: Double) {
    init {
        require(stems.isNotEmpty()) { "A term matches at least one word: $code" }
        require(stems.all { it.isNotBlank() }) { "A stem is not blank: $code" }
        require(weight > 0.0 && weight <= 1.0) { "A weight is evidence between 0 and 1, got $weight for $code" }
    }

    /** Where this term begins in [words], or -1. The first occurrence is all that matters. */
    fun startsIn(words: List<String>): Int {
        val last = words.size - stems.size

        return (0..last).firstOrNull { start ->
            stems.withIndex().all { (offset, stem) -> words[start + offset].startsWith(stem) }
        } ?: NOWHERE
    }

    /** How it reads in the lexicon, which is what a reviewer is shown as the reason. */
    val phrase: String get() = stems.joinToString(" ")

    companion object {
        const val NOWHERE = -1
    }
}
