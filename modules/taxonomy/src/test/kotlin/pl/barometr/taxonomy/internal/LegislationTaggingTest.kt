package pl.barometr.taxonomy.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.shared.Ids
import pl.barometr.shared.PkdCode
import pl.barometr.taxonomy.api.ClassifiedSubject
import pl.barometr.taxonomy.internal.jooq.tables.references.ITEM_INDUSTRY
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What reading an act's title actually writes: a verdict that routes, one that waits
 * for a person, or nothing at all.
 *
 * On a real Postgres because the point of the exercise is the row: until something
 * fills `item_industry`, every consumer downstream — the profile preview, the alert
 * run, the coverage gauge — is exercising machinery over an empty table.
 */
class LegislationTaggingTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val verdicts = IndustryVerdictRepository(dsl)
    private val properties = ClassificationProperties()
    private val catalogue = FakeLegislation()

    private lateinit var tagging: LegislationTagging

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(ITEM_INDUSTRY).execute()
        val meters = SimpleMeterRegistry()
        tagging = LegislationTagging(
            catalogue = catalogue,
            classifier = classifier(),
            classifications = IndustryClassifications(verdicts, properties, meters, clock),
            verdicts = verdicts,
            properties = properties,
            meters = meters,
        )
    }

    @Test
    fun `an act named after its industry is tagged and routes at once`() {
        val act = catalogue.publish("Ustawa o zmianie ustawy o odnawialnych źródłach energii")

        assertTrue(tagging.tagAct(act))

        val subject = ClassifiedSubject(LegislativeKind.ACT, act.value)
        assertEquals(listOf(PkdCode("35")), verdicts.acceptedFor(subject))
        val recorded = verdicts.verdictsFor(subject).single()
        assertEquals(VerdictMethod.MODEL, recorded.method)
        assertEquals(LEXICON, recorded.modelVersion, "which reading said so, findable if it was wrong")
    }

    /**
     * The queue is unworkable without this: "is act 8f3c… about energy" is not a
     * question a subject id and a number let anybody answer.
     */
    @Test
    fun `the verdict says which words caught it`() {
        val act = catalogue.publish("Ustawa o zmianie ustawy o odnawialnych źródłach energii")

        tagging.tagAct(act)

        val recorded = verdicts.verdictsFor(ClassifiedSubject(LegislativeKind.ACT, act.value)).single()
        assertEquals("odnawialnych zrodlach energii", recorded.matchedOn)
    }

    @Test
    fun `a draft that only brushes an industry waits for a person instead of routing`() {
        val draft = catalogue.table("Rozporządzenie w sprawie wymagań dla budownictwa energooszczędnego")

        tagging.tagDraft(draft)

        val subject = ClassifiedSubject(LegislativeKind.DRAFT, draft.value)
        assertEquals(emptyList(), verdicts.acceptedFor(subject), "nothing routes on a guess")
        assertEquals(VerdictStatus.PENDING, verdicts.verdictsFor(subject).single().status)
    }

    @Test
    fun `a law about no industry this lexicon knows is read and left untagged`() {
        val act = catalogue.publish("Ustawa o zmianie ustawy o podatku dochodowym od osób fizycznych")

        assertTrue(tagging.tagAct(act), "reading it and finding nothing is still reading it")

        assertEquals(0, dsl.fetchCount(ITEM_INDUSTRY))
    }

    /**
     * The register restates an act whenever it touches it, and every restatement is an
     * event. Re-recording the same verdicts would reset `decided_at` and move the review
     * queue under whoever is working through it.
     */
    @Test
    fun `a restated act is not read again by the same lexicon`() {
        val act = catalogue.publish("Ustawa o zmianie ustawy o odnawialnych źródłach energii")
        tagging.tagAct(act)
        val first = verdicts.verdictsFor(ClassifiedSubject(LegislativeKind.ACT, act.value)).single().decidedAt

        clock.advanceBy(Duration.ofDays(1))
        assertFalse(tagging.tagAct(act))

        assertEquals(first, verdicts.verdictsFor(ClassifiedSubject(LegislativeKind.ACT, act.value)).single().decidedAt)
    }

    /**
     * The rule that makes the review queue worth working through. A code a reviewer
     * rejected must not come back accepted on the next reading — that would route alerts
     * a person had refused, and nobody would see it happen.
     */
    @Test
    fun `a verdict somebody has decided is not re-decided by the next lexicon`() {
        // A title that only brushes the industry, so the verdict reaches the queue —
        // which is the only kind a reviewer ever gets to decide.
        val act = catalogue.publish("Ustawa o wymaganiach dla budownictwa energooszczędnego")
        val subject = ClassifiedSubject(LegislativeKind.ACT, act.value)
        tagging.tagAct(act)
        val classifications = IndustryClassifications(verdicts, properties, SimpleMeterRegistry(), clock)
        assertTrue(classifications.reviewVerdict(subject, PkdCode("41"), accept = false), "there was one to decide")

        classifications.recordClassification(subject, PkdCode("41"), confidence = 0.99, modelVersion = "pkd-later")

        val standing = verdicts.verdictsFor(subject).single()
        assertEquals(VerdictStatus.REJECTED, standing.status, "the person's decision stands")
        assertEquals(LEXICON, standing.modelVersion)
        assertNotNull(standing.reviewedAt)
    }

    @Test
    fun `an act the catalogue does not hold is not an error`() {
        assertFalse(tagging.tagAct(ActId(Ids.next())))
        assertEquals(0, dsl.fetchCount(ITEM_INDUSTRY))
    }

    private fun classifier() = LexicalIndustryClassifier(
        IndustryLexicon(
            LEXICON,
            listOf(
                IndustryTerm(PkdCode("35"), TitleTokens.of("odnawialnych zrodlach energii"), 0.8),
                IndustryTerm(PkdCode("41"), TitleTokens.of("budownictw"), 0.5),
            ),
        ),
    )

    private companion object {
        const val LEXICON = "test-lexicon-1"
    }
}
