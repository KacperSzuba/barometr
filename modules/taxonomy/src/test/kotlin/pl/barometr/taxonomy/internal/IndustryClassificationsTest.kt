package pl.barometr.taxonomy.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.shared.Ids
import pl.barometr.shared.PkdCode
import pl.barometr.taxonomy.api.ClassifiedSubject
import pl.barometr.taxonomy.internal.jooq.tables.references.ITEM_INDUSTRY
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which industries a law concerns: what routes alerts, what waits for a person, and
 * what the database refuses to hold at all.
 *
 * On a real Postgres because half of what is being tested is the schema: a manual
 * verdict cannot be left pending, a model verdict cannot be anonymous, and a review
 * settles once however many reviewers press at the same moment.
 */
class IndustryClassificationsTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val verdicts = IndustryVerdictRepository(dsl)

    private lateinit var classifications: IndustryClassifications

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(ITEM_INDUSTRY).execute()
        classifications = IndustryClassifications(verdicts, ClassificationProperties(), SimpleMeterRegistry(), clock)
    }

    @Test
    fun `a confident classifier routes alerts, and an unsure one fills a queue`() {
        val confident = subject()
        val unsure = subject()

        classifications.recordClassification(confident, PkdCode("41.20.Z"), confidence = 0.9, modelVersion = "pkd-v1")
        classifications.recordClassification(unsure, PkdCode("41.20.Z"), confidence = 0.3, modelVersion = "pkd-v1")

        assertEquals(listOf(PkdCode("41.20.Z")), verdicts.acceptedFor(confident))
        assertEquals(emptyList(), verdicts.acceptedFor(unsure), "a guess routes nothing")
        assertEquals(listOf(unsure), classifications.pendingReview().map { it.subject })
    }

    @Test
    fun `a person's judgement is accepted as made, and reviewed by the making of it`() {
        val act = subject(LegislativeKind.ACT)

        val verdict = classifications.recordJudgement(act, PkdCode("41.20.Z"))

        assertEquals(VerdictStatus.ACCEPTED, verdict.status)
        assertEquals(1.0, verdict.confidence)
        assertEquals(null, verdict.modelVersion)
        assertTrue(verdict.reviewedAt != null, "the judgement is its own review")
    }

    @Test
    fun `a second reading of the same industry replaces the first`() {
        val draft = subject()

        classifications.recordClassification(draft, PkdCode("41.20.Z"), confidence = 0.2, modelVersion = "pkd-v1")
        classifications.recordClassification(draft, PkdCode("41.20.Z"), confidence = 0.95, modelVersion = "pkd-v2")

        assertEquals(1, verdicts.verdictsFor(draft).size, "one verdict per industry per subject")
        assertEquals(listOf(PkdCode("41.20.Z")), verdicts.acceptedFor(draft))
        assertEquals("pkd-v2", verdicts.verdictsFor(draft).single().modelVersion)
    }

    @Test
    fun `accepting a queued verdict lets it route, and only once`() {
        val draft = subject()
        classifications.recordClassification(draft, PkdCode("41.20.Z"), confidence = 0.3, modelVersion = "pkd-v1")

        assertTrue(classifications.reviewVerdict(draft, PkdCode("41.20.Z"), accept = true))
        assertFalse(
            classifications.reviewVerdict(draft, PkdCode("41.20.Z"), accept = true),
            "a second reviewer changes nothing rather than overwriting the first",
        )
        assertEquals(listOf(PkdCode("41.20.Z")), verdicts.acceptedFor(draft))
    }

    /** A rejection is kept: a wrong tag is what a classifier has to be trained not to repeat. */
    @Test
    fun `a rejected verdict routes nothing and is still on record`() {
        val draft = subject()
        classifications.recordClassification(draft, PkdCode("03.11.Z"), confidence = 0.4, modelVersion = "pkd-v1")

        classifications.reviewVerdict(draft, PkdCode("03.11.Z"), accept = false)

        assertEquals(emptyList(), verdicts.acceptedFor(draft))
        assertEquals(VerdictStatus.REJECTED, verdicts.verdictsFor(draft).single().status)
        assertEquals(emptyList(), classifications.pendingReview())
    }

    /**
     * What a reader is shown beside a law. A verdict nobody has confirmed is a question
     * for a person, and showing it here would make the two look alike.
     */
    @Test
    fun `a subject reads back with the industries somebody stands behind, and no others`() {
        val draft = subject()
        classifications.recordJudgement(draft, PkdCode("41.20.Z"))
        classifications.recordClassification(
            draft,
            PkdCode("35"),
            confidence = 0.9,
            modelVersion = "pkd-v1",
            matchedOn = "odnawialnych zrodlach energii",
        )
        classifications.recordClassification(draft, PkdCode("62"), confidence = 0.2, modelVersion = "pkd-v1")

        val shown = classifications.industriesOf(draft)

        assertEquals(listOf(PkdCode("35"), PkdCode("41.20.Z")), shown.map { it.code })
        assertEquals("odnawialnych zrodlach energii", shown.first { it.code == PkdCode("35") }.matchedOn)
        assertEquals(null, shown.first { it.code == PkdCode("41.20.Z") }.matchedOn, "a person matched nothing")
    }

    @Test
    fun `an industry answers with everything classified beneath it`() {
        val building = subject()
        val roads = subject()
        val fisheries = subject()
        classifications.recordJudgement(building, PkdCode("41.20.Z"))
        classifications.recordJudgement(roads, PkdCode("42.11"))
        classifications.recordJudgement(fisheries, PkdCode("03.11.Z"))

        assertEquals(listOf(building), verdicts.acceptedUnder(PkdCode("41"), limit = 10))
        assertEquals(listOf(roads), verdicts.acceptedUnder(PkdCode("42"), limit = 10))
        assertEquals(listOf(fisheries), verdicts.acceptedUnder(PkdCode("03"), limit = 10))
        assertEquals(listOf(building), verdicts.acceptedUnder(PkdCode("41.20.Z"), limit = 10))
        assertEquals(emptyList(), verdicts.acceptedUnder(PkdCode("62"), limit = 10), "nothing is in IT")
    }

    /**
     * The group level, which the printed code hides: `41.2` is a prefix of `41.20.Z` as
     * text but `"41.2."` is not, and matching on the printed form would answer nothing
     * for anybody who chose an industry at group level.
     */
    @Test
    fun `a group answers with the classes beneath it`() {
        val building = subject()
        classifications.recordJudgement(building, PkdCode("41.20.Z"))

        assertEquals(listOf(building), verdicts.acceptedUnder(PkdCode("41.2"), limit = 10))
    }

    @Test
    fun `a queued verdict routes nothing until somebody looks at it`() {
        val draft = subject()
        classifications.recordClassification(draft, PkdCode("41.20.Z"), confidence = 0.1, modelVersion = "pkd-v1")

        assertEquals(emptyList(), verdicts.acceptedUnder(PkdCode("41"), limit = 10))
    }

    /**
     * A person deciding *is* the review. A manual verdict left pending would be a human
     * judgement queued for a human judgement, and the database is where that is stated.
     */
    @Test
    fun `a manual verdict cannot be left pending`() {
        assertFailsWith<DataAccessException> {
            dsl.insertInto(ITEM_INDUSTRY)
                .set(ITEM_INDUSTRY.SUBJECT_KIND, LegislativeKind.DRAFT)
                .set(ITEM_INDUSTRY.SUBJECT_ID, Ids.next())
                .set(ITEM_INDUSTRY.PKD, "41.20.Z")
                .set(ITEM_INDUSTRY.STATUS, VerdictStatus.PENDING.wireName)
                .set(ITEM_INDUSTRY.CONFIDENCE, 1.0f)
                .set(ITEM_INDUSTRY.METHOD, VerdictMethod.MANUAL.wireName)
                .set(ITEM_INDUSTRY.DECIDED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .execute()
        }
    }

    /**
     * A person read the law and decided; they matched no phrase. Recording one against
     * their verdict would be this system inventing a reason on somebody else's behalf.
     */
    @Test
    fun `a person's judgement cannot carry a phrase a classifier matched`() {
        assertFailsWith<DataAccessException> {
            dsl.insertInto(ITEM_INDUSTRY)
                .set(ITEM_INDUSTRY.SUBJECT_KIND, LegislativeKind.ACT)
                .set(ITEM_INDUSTRY.SUBJECT_ID, Ids.next())
                .set(ITEM_INDUSTRY.PKD, "41.20.Z")
                .set(ITEM_INDUSTRY.STATUS, VerdictStatus.ACCEPTED.wireName)
                .set(ITEM_INDUSTRY.CONFIDENCE, 1.0f)
                .set(ITEM_INDUSTRY.METHOD, VerdictMethod.MANUAL.wireName)
                .set(ITEM_INDUSTRY.MATCHED_ON, "prawo budowlane")
                .set(ITEM_INDUSTRY.DECIDED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .set(ITEM_INDUSTRY.REVIEWED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .execute()
        }
    }

    @Test
    fun `a model verdict has to name its model`() {
        assertFailsWith<DataAccessException> {
            dsl.insertInto(ITEM_INDUSTRY)
                .set(ITEM_INDUSTRY.SUBJECT_KIND, LegislativeKind.DRAFT)
                .set(ITEM_INDUSTRY.SUBJECT_ID, Ids.next())
                .set(ITEM_INDUSTRY.PKD, "41.20.Z")
                .set(ITEM_INDUSTRY.STATUS, VerdictStatus.PENDING.wireName)
                .set(ITEM_INDUSTRY.CONFIDENCE, 0.5f)
                .set(ITEM_INDUSTRY.METHOD, VerdictMethod.MODEL.wireName)
                .set(ITEM_INDUSTRY.DECIDED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .execute()
        }
    }

    /** A section letter is not a code this system can match, and the column says so too. */
    @Test
    fun `something that is not an industry code is refused by the schema`() {
        assertFailsWith<DataAccessException> {
            dsl.insertInto(ITEM_INDUSTRY)
                .set(ITEM_INDUSTRY.SUBJECT_KIND, LegislativeKind.DRAFT)
                .set(ITEM_INDUSTRY.SUBJECT_ID, Ids.next())
                .set(ITEM_INDUSTRY.PKD, "J")
                .set(ITEM_INDUSTRY.STATUS, VerdictStatus.ACCEPTED.wireName)
                .set(ITEM_INDUSTRY.CONFIDENCE, 1.0f)
                .set(ITEM_INDUSTRY.METHOD, VerdictMethod.MANUAL.wireName)
                .set(ITEM_INDUSTRY.DECIDED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
                .execute()
        }
    }

    private fun subject(kind: String = LegislativeKind.DRAFT): ClassifiedSubject =
        ClassifiedSubject(kind, UUID.randomUUID())
}
