package pl.barometr.corpus.internal.diff

import pl.barometr.corpus.internal.structure.ComparableText
import pl.barometr.corpus.internal.structure.EditorialUnit
import pl.barometr.corpus.internal.structure.Word

/**
 * One editorial unit as the comparison holds it: where it is, what it says, and the
 * two readings of what it says.
 *
 * Computed once per unit and carried, rather than recomputed inside the matching
 * loops. A three-hundred-page bill has some thousands of units, and folding the text
 * of each on every candidate comparison is the difference between a comparison that
 * runs in a job and one that runs in an afternoon.
 */
data class UnitReading(
    val unit: EditorialUnit,
    val words: List<Word>,
    /** What two units are the same by: their words, case folded, designator dropped. */
    val comparable: String,
    /** The same without punctuation — what decides whether a difference is worth reporting. */
    val core: String,
    val shingles: Set<Int>,
) {
    val path: String get() = unit.path.value

    companion object {
        fun of(text: String, unit: EditorialUnit): UnitReading {
            val words = ComparableText.wordsIn(text, unit.charStart, unit.charEnd)
            return UnitReading(
                unit = unit,
                words = words,
                comparable = ComparableText.comparableOf(words),
                core = ComparableText.coreOf(words),
                shingles = TextFingerprint.shinglesOf(words),
            )
        }
    }
}
