package pl.barometr.taxonomy.internal

import org.junit.jupiter.api.Test
import pl.barometr.shared.PkdCode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a title becomes a set of industries, and how sure that makes the answer.
 *
 * Over a lexicon written here rather than the one that ships: what is under test is the
 * arithmetic and the matching, and pinning those to terms somebody will edit next month
 * would make an improvement to the lexicon look like a broken test. The shipped file has
 * its own test, and it asks a different question.
 */
class LexicalIndustryClassifierTest {

    /**
     * Polish inflects everything a law is about, so a lexicon of whole words would
     * match the nominative and miss every title ever written.
     */
    @Test
    fun `a stem matches the case the title happens to be in`() {
        val found = classifier(term("41", "budowlan", 0.5))
            .industriesIn("Ustawa o zmianie ustawy — Prawo budowlane")

        assertEquals(listOf(PkdCode("41")), found.map { it.code })
    }

    @Test
    fun `a phrase matches only where its words run together`() {
        val classifier = classifier(term("49", "transporcie drogow", 0.8))

        assertEquals(1, classifier.industriesIn("o transporcie drogowym").size)
        assertEquals(
            0,
            classifier.industriesIn("o transporcie kolejowym i o ruchu drogowym").size,
            "both words are there and the phrase is not",
        )
    }

    /**
     * Two hints are surer than either alone and neither is certainty. Adding the weights
     * would let three weak stems outrank the phrase a law is named after — and cross the
     * acceptance threshold on evidence nobody would accept.
     */
    @Test
    fun `evidence combines rather than adding up`() {
        val found = classifier(
            term("41", "budowlan", 0.5),
            term("41", "budownictw", 0.5),
        ).industriesIn("Ustawa o wyrobach budowlanych w budownictwie mieszkaniowym")

        assertEquals(0.75, found.single().confidence, absoluteTolerance = 1e-9)
    }

    @Test
    fun `the same term twice in one title is one piece of evidence`() {
        val found = classifier(term("41", "budowlan", 0.5))
            .industriesIn("Prawo budowlane oraz przepisy budowlane")

        assertEquals(0.5, found.single().confidence, absoluteTolerance = 1e-9)
    }

    @Test
    fun `the surest industry is first, because that is the one a reader is shown`() {
        val found = classifier(
            term("35", "energii elektryczn", 0.8),
            term("41", "budowlan", 0.4),
        ).industriesIn("Ustawa o cenach energii elektrycznej dla obiektów budowlanych")

        assertEquals(listOf(PkdCode("35"), PkdCode("41")), found.map { it.code })
    }

    @Test
    fun `what matched travels with the verdict, because somebody has to review it`() {
        val found = classifier(term("86", "opieki zdrowotn", 0.8))
            .industriesIn("Ustawa o świadczeniach opieki zdrowotnej")

        assertEquals(listOf("opieki zdrowotn"), found.single().reasons)
    }

    @Test
    fun `a title about nothing this lexicon knows classifies as nothing`() {
        val found = classifier(term("41", "budowlan", 0.5))
            .industriesIn("Ustawa o zmianie ustawy o podatku dochodowym od osób fizycznych")

        assertTrue(found.isEmpty())
    }

    @Test
    fun `an empty title is not an error, it is an answer`() {
        assertTrue(classifier(term("41", "budowlan", 0.5)).industriesIn("   ").isEmpty())
    }

    private fun classifier(vararg terms: IndustryTerm) =
        LexicalIndustryClassifier(IndustryLexicon("test-lexicon", terms.toList()))

    private fun term(code: String, phrase: String, weight: Double) =
        IndustryTerm(PkdCode(code), TitleTokens.of(phrase), weight)
}
