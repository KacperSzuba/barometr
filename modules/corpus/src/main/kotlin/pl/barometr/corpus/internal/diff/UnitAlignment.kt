package pl.barometr.corpus.internal.diff

import org.springframework.stereotype.Component

/**
 * Decides which unit of the older version is which unit of the newer one.
 *
 * This is the whole difference between a diff a lawyer reads and one nobody opens. An
 * insertion at article 5 renumbers everything after it; compared position by position,
 * or matched by number alone, that is three hundred pages deleted and three hundred
 * pages added. Matched by what the units say, it is one insertion and a renumbering.
 *
 * **Two passes, in this order.** Units whose paths agree are the same unit, because the
 * document says so — that settles most of a typical revision for the cost of a hash
 * lookup. Only what is left goes through content matching, which is the expensive half
 * and the one that can be wrong.
 *
 * **Content matching is indexed, not quadratic.** Candidates come from shared
 * three-word runs ([TextFingerprint]); each candidate pair is then scored properly, the
 * proposals are taken best-first, and each unit is claimed once. A version pair with a
 * few thousand units on each side would be millions of comparisons done exhaustively,
 * which is the reason this is written as an index rather than as two nested loops.
 *
 * Unchanged units are not returned at all. What comes back is the changes, in the order
 * a reader walks the newer version, with a removal sitting where the text it left is.
 */
@Component
class UnitAlignment(private val properties: DiffProperties) {

    fun alignedTo(before: List<UnitReading>, after: List<UnitReading>): List<AlignedUnits> {
        val newerOf = IntArray(before.size) { UNMATCHED }
        val olderOf = IntArray(after.size) { UNMATCHED }
        val confidence = DoubleArray(after.size) { Double.NaN }

        matchByPath(before, after, newerOf, olderOf) { older, newer -> older.comparable == newer.comparable }
        matchByContent(before, after, newerOf, olderOf, confidence)
        matchWordlessInOrder(before, after, newerOf, olderOf)
        // Last: an address that still has a unit on both sides, however differently it
        // now reads. A whole article replaced in place is one modification, and saying
        // so is better than an unexplained removal beside an unexplained addition.
        matchByPath(before, after, newerOf, olderOf) { _, _ -> true }

        return changesIn(before, after, newerOf, olderOf, confidence)
    }

    /**
     * The same address in both versions, the same rank of unit at it, and whatever else
     * [accept] insists on.
     *
     * Run twice, and the order matters. First for units that also read identically —
     * those are settled, and settling them keeps them out of the content matching where
     * they could be stolen by a better-scoring candidate. Then, after content matching,
     * for whatever is left at an address on both sides.
     *
     * Matching every shared path outright would be the natural first pass and it is
     * wrong: an insertion renumbers the whole document, so `art-6` before and `art-6`
     * after are usually two different articles, and pairing them buries the move this
     * whole class exists to find.
     */
    private fun matchByPath(
        before: List<UnitReading>,
        after: List<UnitReading>,
        newerOf: IntArray,
        olderOf: IntArray,
        accept: (older: UnitReading, newer: UnitReading) -> Boolean,
    ) {
        val addresses = HashMap<String, Int>(before.size)
        before.forEachIndexed { older, reading ->
            if (newerOf[older] == UNMATCHED) addresses.putIfAbsent(reading.path, older)
        }

        after.forEachIndexed { newer, reading ->
            if (olderOf[newer] != UNMATCHED) return@forEachIndexed
            val older = addresses[reading.path] ?: return@forEachIndexed

            if (
                newerOf[older] == UNMATCHED &&
                before[older].unit.kind == reading.unit.kind &&
                accept(before[older], reading)
            ) {
                newerOf[older] = newer
                olderOf[newer] = older
            }
        }
    }

    /**
     * What is left, matched by what it says.
     *
     * Proposals are taken best-first and each unit is claimed once, so a paragraph that
     * two others both resemble goes to the one it resembles most — and the other is
     * reported as added, which is the honest answer rather than the convenient one.
     */
    private fun matchByContent(
        before: List<UnitReading>,
        after: List<UnitReading>,
        newerOf: IntArray,
        olderOf: IntArray,
        confidence: DoubleArray,
    ) {
        val postings = postingsOf(before, newerOf)
        val proposals = mutableListOf<Proposal>()

        after.forEachIndexed { newer, reading ->
            if (olderOf[newer] != UNMATCHED) return@forEachIndexed

            candidatesFor(reading, before, postings).forEach { older ->
                val score = TextFingerprint.similarity(before[older].shingles, reading.shingles)
                if (score >= properties.matchFloor) proposals += Proposal(older, newer, score)
            }
        }

        proposals.sortedByDescending(Proposal::score).forEach { proposal ->
            if (newerOf[proposal.older] == UNMATCHED && olderOf[proposal.newer] == UNMATCHED) {
                newerOf[proposal.older] = proposal.newer
                olderOf[proposal.newer] = proposal.older
                confidence[proposal.newer] = proposal.score
            }
        }
    }

    /**
     * The units that say nothing of their own, paired in the order they appear.
     *
     * An article whose first paragraph is written on its own line — `Art. 6. 1. Wniosek…`
     * — is a unit whose own words are just `Art. 6.`, and once the designator is folded
     * away it has no content at all. Content matching cannot see such a unit, and
     * matching it by address would pair the lead-in of the old article 6 with a
     * *different* article that now holds that number, reporting a rewrite that never
     * happened.
     *
     * Their order is the only thing left to go on, and it is enough: they are lead-ins,
     * and the article they lead into is matched on its own merits.
     */
    private fun matchWordlessInOrder(
        before: List<UnitReading>,
        after: List<UnitReading>,
        newerOf: IntArray,
        olderOf: IntArray,
    ) {
        val waiting = after.indices
            .filter { olderOf[it] == UNMATCHED && after[it].comparable.isEmpty() }
            .groupBy { after[it].unit.kind }
            .mapValues { (_, indices) -> ArrayDeque(indices) }

        before.indices.forEach { older ->
            if (newerOf[older] != UNMATCHED || before[older].comparable.isNotEmpty()) return@forEach

            waiting[before[older].unit.kind]?.removeFirstOrNull()?.let { newer ->
                newerOf[older] = newer
                olderOf[newer] = older
            }
        }
    }

    /**
     * Which unmatched units of the older version carry each three-word run.
     *
     * Postings are capped: a run of boilerplate that appears in four hundred units
     * says nothing about which of them is which, and following all of it would be the
     * quadratic comparison this index exists to avoid.
     */
    private fun postingsOf(before: List<UnitReading>, newerOf: IntArray): Map<Int, List<Int>> {
        val postings = HashMap<Int, MutableList<Int>>()

        before.forEachIndexed { older, reading ->
            if (newerOf[older] != UNMATCHED) return@forEachIndexed
            reading.shingles.forEach { shingle ->
                val holders = postings.getOrPut(shingle) { mutableListOf() }
                if (holders.size < MAX_POSTINGS) holders += older
            }
        }

        return postings
    }

    private fun candidatesFor(
        reading: UnitReading,
        before: List<UnitReading>,
        postings: Map<Int, List<Int>>,
    ): List<Int> {
        val shared = HashMap<Int, Int>()

        reading.shingles.forEach { shingle ->
            postings[shingle]?.forEach { older ->
                if (before[older].unit.kind == reading.unit.kind) shared.merge(older, 1, Int::plus)
            }
        }

        return shared.entries
            .sortedByDescending(Map.Entry<Int, Int>::value)
            .take(MAX_CANDIDATES)
            .map(Map.Entry<Int, Int>::key)
    }

    /**
     * The changes, in the order the newer version is read.
     *
     * A removal has no place of its own in that order, so it is written where the text
     * it left was: after the last unit that survived before it. Anything else puts
     * every deletion at the end, which is where they are least readable.
     */
    private fun changesIn(
        before: List<UnitReading>,
        after: List<UnitReading>,
        newerOf: IntArray,
        olderOf: IntArray,
        confidence: DoubleArray,
    ): List<AlignedUnits> {
        val removalsBefore = Array(after.size + 1) { mutableListOf<UnitReading>() }
        var following = 0

        before.forEachIndexed { older, reading ->
            val newer = newerOf[older]
            if (newer == UNMATCHED) removalsBefore[following] += reading else following = newer + 1
        }

        val changes = mutableListOf<AlignedUnits>()
        (0..after.size).forEach { newer ->
            removalsBefore[newer].forEach { changes += AlignedUnits(it, null, null) }
            if (newer == after.size) return@forEach

            val older = olderOf[newer]
            when {
                older == UNMATCHED -> changes += AlignedUnits(null, after[newer], null)
                isUnchanged(before[older], after[newer]) -> Unit
                else -> changes += AlignedUnits(
                    before = before[older],
                    after = after[newer],
                    similarity = confidence[newer].takeUnless(Double::isNaN),
                )
            }
        }

        return changes
    }

    /** Same place, same words: nothing happened here, and nothing is reported. */
    private fun isUnchanged(before: UnitReading, after: UnitReading): Boolean =
        before.path == after.path && before.comparable == after.comparable

    private data class Proposal(val older: Int, val newer: Int, val score: Double)

    private companion object {
        const val UNMATCHED = -1

        /**
         * How many units one three-word run may point at before the run is treated as
         * boilerplate. Fifty is far more than a real phrase appears in and far less
         * than "w rozumieniu ustawy" does.
         */
        const val MAX_POSTINGS = 50

        /** How many of the best-connected candidates are scored properly, per unit. */
        const val MAX_CANDIDATES = 25
    }
}
