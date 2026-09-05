package pl.barometr.corpus.internal.diff

import pl.barometr.corpus.api.ChangeKind
import pl.barometr.corpus.internal.structure.EditorialUnitReader
import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The size the specification names: documents run to three hundred pages, and the
 * comparison has to finish in a job rather than in an afternoon.
 *
 * The pair is the worst realistic case rather than an average one — an article inserted
 * at the very top, which renumbers every article after it. Nothing can be matched by
 * address, so the whole document goes through content matching, which is the pass that
 * would be quadratic if it were written as two loops. That is what this measures.
 *
 * The bound is deliberately loose. What it is protecting against is an algorithm that
 * degrades with the square of the document, which on this input is minutes rather than
 * seconds — not a regression of twenty per cent, which would make the test flaky on a
 * busy build machine and tell nobody anything.
 */
class VersionDiffPerformanceTest {

    private val reader = EditorialUnitReader()
    private val alignment = UnitAlignment(DiffProperties())
    private val words = WordLevelChanges(DiffProperties())

    @Test
    fun `three hundred pages renumbered from the first article are compared in seconds`() {
        val before = bill(articles = ARTICLES, from = 1, edited = emptySet())
        val after = INSERTED + bill(articles = ARTICLES, from = 2, edited = setOf(7, 500, 2_000))

        assertTrue(before.length > 700_000, "a three-hundred-page bill is about that many characters")

        lateinit var changes: List<AlignedUnits>
        val took = measureTimeMillis { changes = align(before, after) }

        assertEquals(0, changes.count { it.kind == ChangeKind.REMOVED }, "a renumbering deletes nothing")
        assertEquals(1, changes.count { it.kind == ChangeKind.ADDED }, "one article was inserted")
        assertEquals(3, changes.count { it.kind == ChangeKind.MODIFIED }, "three articles were edited")
        assertTrue(took < BUDGET_MS, "comparing three hundred pages took ${took}ms")
    }

    private fun align(before: String, after: String) =
        alignment.alignedTo(
            reader.unitsIn(before).map { UnitReading.of(before, it) },
            reader.unitsIn(after).map { UnitReading.of(after, it) },
        ).onEach { aligned ->
            // The word diff is part of the work a real comparison does, so it is part of
            // what is being timed.
            if (aligned.kind == ChangeKind.MODIFIED && aligned.before != null && aligned.after != null) {
                words.changesWithin(aligned.before, aligned.after)
            }
        }

    /**
     * A bill of [articles] articles, each a paragraph of plausible statutory prose drawn
     * from a fixed vocabulary with a fixed seed — so the document is the same on every
     * run, and a failure is reproducible.
     */
    private fun bill(articles: Int, from: Int, edited: Set<Int>): String {
        val random = Random(SEED)

        return (1..articles).joinToString("\n") { index ->
            val sentence = (1..SENTENCE_WORDS).joinToString(" ") { WORDS[random.nextInt(WORDS.size)] }
            val body = if (index in edited) "$sentence w terminie trzydziestu dni" else sentence
            "Art. ${index + from - 1}. $body."
        }
    }

    private companion object {
        /** Roughly three hundred pages of a statute at two and a half thousand characters a page. */
        const val ARTICLES = 3_000
        const val SENTENCE_WORDS = 34
        const val SEED = 20_260_305
        const val BUDGET_MS = 30_000L

        val INSERTED = "Art. 1. Minister właściwy do spraw klimatu prowadzi rejestr podmiotów " +
            "zwolnionych z obowiązku prowadzenia ewidencji odpadów w postaci elektronicznej.\n"

        val WORDS = listOf(
            "przedsiębiorca", "prowadzi", "ewidencję", "odpadów", "w", "postaci", "elektronicznej",
            "minister", "właściwy", "do", "spraw", "klimatu", "ogłasza", "wykaz", "podmiotów",
            "zwolnionych", "z", "obowiązku", "sprawozdawczego", "na", "podstawie", "przepisów",
            "ustawy", "o", "odpadach", "oraz", "przekazuje", "dane", "wojewodzie", "właściwemu",
            "ze", "względu", "miejsce", "wytworzenia", "odpadu", "w", "roku", "kalendarzowym",
        )
    }
}
