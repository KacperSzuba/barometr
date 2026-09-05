package pl.barometr.taxonomy.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.shared.PkdCode
import pl.barometr.taxonomy.internal.jooq.tables.references.CLASSIFICATION_PROGRESS
import pl.barometr.taxonomy.internal.jooq.tables.references.ITEM_INDUSTRY
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Reading the archive that was already there when the classifier arrived — which on the
 * day it ships is all of it, and after that is nothing.
 *
 * What is under test is the walk: that it gets through the backlog in bounded runs, that
 * a restart resumes rather than starting over, that it stops when it runs out of
 * archive, and that correcting the lexicon is what makes it read everything again.
 */
class BacklogClassificationTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val verdicts = IndustryVerdictRepository(dsl)
    private val progress = ClassificationProgressRepository(dsl, clock)
    private val catalogue = FakeLegislation()

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(ITEM_INDUSTRY).execute()
        dsl.deleteFrom(CLASSIFICATION_PROGRESS).execute()
    }

    @Test
    fun `everything already in the archive is classified`() {
        catalogue.publish("Ustawa o zmianie ustawy o odnawialnych źródłach energii")
        catalogue.table("Rządowy projekt ustawy o zmianie ustawy — Prawo budowlane")

        sweep().classifyWhatTheArchiveAlreadyHolds()

        assertEquals(2, dsl.fetchCount(ITEM_INDUSTRY))
        assertEquals(setOf(PkdCode("35"), PkdCode("41")), dsl.select(ITEM_INDUSTRY.PKD).from(ITEM_INDUSTRY)
            .fetch { PkdCode(it.value1()!!) }.toSet())
    }

    /**
     * A run reads what its budget allows and writes down where it stopped, so the next
     * one carries on instead of paging over what it has already read.
     */
    @Test
    fun `a bounded run leaves its position for the next one`() {
        repeat(3) { catalogue.publish("Ustawa o zmianie ustawy o odnawialnych źródłach energii") }
        val oneAtATime = sweep(subjectsPerSweep = 1)

        oneAtATime.classifyWhatTheArchiveAlreadyHolds()
        assertEquals(1, dsl.fetchCount(ITEM_INDUSTRY, ITEM_INDUSTRY.SUBJECT_KIND.eq(LegislativeKind.ACT)))
        assertNotNull(positionOf(LegislativeKind.ACT), "where it got to")

        oneAtATime.classifyWhatTheArchiveAlreadyHolds()
        assertEquals(2, dsl.fetchCount(ITEM_INDUSTRY, ITEM_INDUSTRY.SUBJECT_KIND.eq(LegislativeKind.ACT)))
    }

    /**
     * The steady state. Without the completion mark the walk's only ending is the end of
     * the archive, so it would page through everything ever stored to discover there is
     * nothing left to read.
     */
    @Test
    fun `a walk that ran out of archive does not start again`() {
        catalogue.publish("Ustawa o zmianie ustawy o odnawialnych źródłach energii")
        val sweep = sweep()
        sweep.classifyWhatTheArchiveAlreadyHolds()

        sweep.classifyWhatTheArchiveAlreadyHolds()

        assertNotNull(completionOf(LegislativeKind.ACT))
        assertEquals(1, dsl.fetchCount(ITEM_INDUSTRY))
    }

    /**
     * The intended way to improve coverage: correct the terms, and the whole archive is
     * read again against them. The old version's progress stays as the record of what it
     * got through.
     */
    @Test
    fun `a corrected lexicon reads the archive from the beginning`() {
        catalogue.publish("Ustawa o zmianie ustawy o wyrobach budowlanych")
        sweep(lexicon = "pkd-1").classifyWhatTheArchiveAlreadyHolds()
        assertEquals(0, dsl.fetchCount(ITEM_INDUSTRY), "the first lexicon knew nothing about building work")

        sweep(lexicon = "pkd-2", term = IndustryTerm(PkdCode("41"), TitleTokens.of("wyrobach budowlan"), 0.8))
            .classifyWhatTheArchiveAlreadyHolds()

        assertEquals(1, dsl.fetchCount(ITEM_INDUSTRY))
        assertNotNull(completionOf(LegislativeKind.ACT, lexicon = "pkd-1"), "the first walk's record stands")
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    private fun sweep(
        lexicon: String = "test-lexicon-1",
        subjectsPerSweep: Int = 100,
        term: IndustryTerm = IndustryTerm(PkdCode("35"), TitleTokens.of("odnawialnych zrodlach energii"), 0.8),
    ): UnclassifiedBacklogSweep {
        val meters = SimpleMeterRegistry()
        val properties = ClassificationProperties(subjectsPerSweep = subjectsPerSweep)
        val classifier = LexicalIndustryClassifier(
            IndustryLexicon(
                lexicon,
                listOf(term, IndustryTerm(PkdCode("41"), TitleTokens.of("prawo budowlane"), 0.8)),
            ),
        )

        return UnclassifiedBacklogSweep(
            catalogue = catalogue,
            tagging = LegislationTagging(
                catalogue = catalogue,
                classifier = classifier,
                classifications = IndustryClassifications(verdicts, properties, meters, clock),
                verdicts = verdicts,
                properties = properties,
                meters = meters,
            ),
            classifier = classifier,
            progress = progress,
            properties = properties,
            meters = meters,
        )
    }

    private fun positionOf(kind: String, lexicon: String = "test-lexicon-1") =
        dsl.select(CLASSIFICATION_PROGRESS.LAST_SUBJECT_ID)
            .from(CLASSIFICATION_PROGRESS)
            .where(CLASSIFICATION_PROGRESS.LEXICON_VERSION.eq(lexicon))
            .and(CLASSIFICATION_PROGRESS.SUBJECT_KIND.eq(kind))
            .fetchOne()
            ?.value1()

    private fun completionOf(kind: String, lexicon: String = "test-lexicon-1") =
        dsl.select(CLASSIFICATION_PROGRESS.COMPLETED_AT)
            .from(CLASSIFICATION_PROGRESS)
            .where(CLASSIFICATION_PROGRESS.LEXICON_VERSION.eq(lexicon))
            .and(CLASSIFICATION_PROGRESS.SUBJECT_KIND.eq(kind))
            .fetchOne()
            ?.value1()
}
