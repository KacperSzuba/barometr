package pl.barometr.taxonomy.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.shared.Ids
import pl.barometr.shared.PkdCode
import pl.barometr.taxonomy.api.ClassifiedSubject
import pl.barometr.taxonomy.internal.jooq.tables.references.ITEM_INDUSTRY
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What a reviewer is handed.
 *
 * The queue exists so that a verdict nobody was sure about is decided by a person
 * rather than routed or dropped, and that only works if deciding one takes seconds. A
 * subject id, a code and a number take a search in another system, per row, on a queue
 * a backlog run fills a thousand at a time.
 */
class ClassificationReviewQueueTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val verdicts = IndustryVerdictRepository(dsl)
    private val catalogue = FakeLegislation()

    private lateinit var classifications: IndustryClassifications
    private lateinit var queue: ClassificationReviewQueue

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(ITEM_INDUSTRY).execute()
        classifications = IndustryClassifications(
            verdicts,
            ClassificationProperties(),
            SimpleMeterRegistry(),
            clock,
        )
        queue = ClassificationReviewQueue(classifications, catalogue)
    }

    @Test
    fun `a waiting verdict is shown with the law's name and the words that caught it`() {
        val act = catalogue.publish("Ustawa o wyrobach budowlanych")
        classifications.recordClassification(
            subject = ClassifiedSubject(LegislativeKind.ACT, act.value),
            code = PkdCode("41"),
            confidence = 0.4,
            modelVersion = "pkd-lexical-1",
            matchedOn = "budowlan",
        )

        val waiting = queue.awaitingReview().single()

        assertEquals("Ustawa o wyrobach budowlanych", waiting.title)
        assertEquals("budowlan", waiting.verdict.matchedOn)
        assertEquals(PkdCode("41"), waiting.verdict.code)
    }

    @Test
    fun `a draft is looked up in its own half of the catalogue`() {
        val draft = catalogue.table("Projekt ustawy o kredycie konsumenckim")
        classifications.recordClassification(
            subject = ClassifiedSubject(LegislativeKind.DRAFT, draft.value),
            code = PkdCode("64"),
            confidence = 0.5,
            modelVersion = "pkd-lexical-1",
            matchedOn = "kredycie konsumenckim",
        )

        assertEquals("Projekt ustawy o kredycie konsumenckim", queue.awaitingReview().single().title)
    }

    /**
     * A row the catalogue cannot name is still a decision waiting to be made. Dropping
     * it would hide the verdict; failing would hide the whole queue behind one row.
     */
    @Test
    fun `a subject the catalogue does not hold keeps its place in the queue`() {
        classifications.recordClassification(
            subject = ClassifiedSubject(LegislativeKind.ACT, Ids.next()),
            code = PkdCode("41"),
            confidence = 0.4,
            modelVersion = "pkd-lexical-1",
            matchedOn = "budowlan",
        )

        val waiting = queue.awaitingReview().single()

        assertNull(waiting.title)
        assertEquals("budowlan", waiting.verdict.matchedOn)
    }

    @Test
    fun `what routes on its own is not in the queue`() {
        val act = catalogue.publish("Ustawa o odnawialnych źródłach energii")
        classifications.recordClassification(
            subject = ClassifiedSubject(LegislativeKind.ACT, act.value),
            code = PkdCode("35"),
            confidence = 0.9,
            modelVersion = "pkd-lexical-1",
            matchedOn = "odnawialnych zrodlach energii",
        )

        assertEquals(emptyList(), queue.awaitingReview())
    }
}
