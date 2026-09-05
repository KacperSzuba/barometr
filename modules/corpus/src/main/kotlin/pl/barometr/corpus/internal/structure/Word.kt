package pl.barometr.corpus.internal.structure

/**
 * One word of a unit, in the two forms a comparison needs, with the offsets it came
 * from.
 *
 * [value] is the word as text compares it — case folded, soft hyphens and the line
 * breaks a PDF put inside it removed. [core] is the same word without punctuation,
 * which is what answers the second question: whether the change is worth anybody's
 * attention or is a comma.
 *
 * Both are derived from the same slice of the same string, so "the text changed" and
 * "the change is substantive" can never be answers to two different readings.
 */
data class Word(
    val value: String,
    val core: String,
    val charStart: Int,
    val charEnd: Int,
)
