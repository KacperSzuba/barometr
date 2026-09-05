package pl.barometr.taxonomy.internal

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lexicon that ships, read the way the application reads it.
 *
 * Two questions, and the second is the one worth having. That the file parses and its
 * codes are codes is the cheap half — a typo there would take the application down at
 * startup, which is the intended failure but a slow way to find out. The half that
 * earns its place is a handful of real titles: the terms are meant to be edited, and an
 * edit that quietly stops matching "prawo budowlane" is exactly the regression nobody
 * would otherwise notice until the coverage gauge drifted.
 */
class PkdLexiconTest {

    private val lexicon = IndustryLexicon.readFrom(
        checkNotNull(javaClass.getResourceAsStream("/taxonomy/pkd-lexicon.json")) { "the shipped lexicon" },
        JsonMapper.builder().build(),
    )
    private val classifier = LexicalIndustryClassifier(lexicon)

    @Test
    fun `the shipped lexicon is loadable, versioned and free of repeated terms`() {
        assertTrue(lexicon.version.isNotBlank())
        assertTrue(lexicon.terms.size > 50, "a lexicon this small would classify almost nothing")
    }

    /**
     * The stems are compared against normalised words, so a stem written with a
     * diacritic or a capital would match nothing at all — silently, for ever.
     */
    @Test
    fun `every stem is written the way a normalised title is`() {
        lexicon.terms.forEach { term ->
            assertEquals(term.stems, TitleTokens.of(term.phrase), "${term.code}: ${term.phrase}")
        }
    }

    @Test
    fun `a law named after its industry is classified without asking anyone`() {
        val accepted = ClassificationProperties().acceptanceThreshold

        listOf(
            "Ustawa o zmianie ustawy o odnawialnych źródłach energii" to "35",
            "Rządowy projekt ustawy o zmianie ustawy — Prawo budowlane" to "41",
            "Ustawa o świadczeniach opieki zdrowotnej finansowanych ze środków publicznych" to "86",
            "Projekt ustawy o zmianie ustawy o kredycie konsumenckim" to "64",
            "Ustawa o zmianie ustawy o transporcie drogowym oraz o czasie pracy kierowców" to "49",
            "Ustawa o zmianie ustawy o systemie oświaty" to "85",
        ).forEach { (title, code) ->
            val best = classifier.industriesIn(title).firstOrNull()

            assertEquals(code, best?.code?.value, title)
            assertTrue(best != null && best.confidence >= accepted, "$title scored ${best?.confidence}")
        }
    }

    /**
     * A lone weak stem is a question, not an answer: a building code that mentions
     * energy efficiency is not an energy law, and the review queue is where that gets
     * decided.
     */
    @Test
    fun `a title that only brushes an industry waits for a person`() {
        val properties = ClassificationProperties()
        val best = classifier.industriesIn("Rozporządzenie w sprawie wymagań dla budownictwa energooszczędnego")
            .first()

        assertTrue(best.confidence >= properties.floorConfidence, "worth asking about")
        assertTrue(best.confidence < properties.acceptanceThreshold, "not worth routing on")
    }

    @Test
    fun `a law about none of these industries is classified as none of them`() {
        assertTrue(
            classifier.industriesIn("Ustawa o zmianie ustawy o podatku dochodowym od osób fizycznych").isEmpty(),
        )
    }
}
