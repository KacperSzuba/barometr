package pl.barometr.corpus.internal.diff

import pl.barometr.corpus.internal.structure.Word

/**
 * How a unit is recognised again after it has been renumbered and edited.
 *
 * Overlapping runs of three words, hashed. Two units that share most of their runs
 * said most of the same things in most of the same order, which is exactly the
 * question alignment asks — and unlike a similarity computed over whole texts, it can
 * be answered by an inverted index instead of by comparing every unit with every other
 * one. That difference is quadratic, and at a few thousand units per version it is the
 * whole performance budget.
 *
 * A unit shorter than a run is fingerprinted by its own words. A one-word tiret is
 * still a unit somebody may have moved.
 */
object TextFingerprint {

    fun shinglesOf(words: List<Word>): Set<Int> {
        val values = words.map { it.value }
        if (values.size < RUN) return values.map { it.hashCode() }.toSet()

        return (0..values.size - RUN)
            .map { start -> values.subList(start, start + RUN).joinToString(" ").hashCode() }
            .toSet()
    }

    /**
     * Sørensen–Dice over the two fingerprints: twice what they share, over what they
     * hold between them. Chosen over Jaccard because it is the more forgiving of the
     * two about one side being longer, and a redrafted paragraph is usually longer.
     */
    fun similarity(first: Set<Int>, second: Set<Int>): Double {
        if (first.isEmpty() || second.isEmpty()) return 0.0
        val shared = if (first.size < second.size) first.count(second::contains) else second.count(first::contains)
        return 2.0 * shared / (first.size + second.size)
    }

    /** Three words: long enough that a shared run means something, short enough that a short point has runs at all. */
    private const val RUN = 3
}
