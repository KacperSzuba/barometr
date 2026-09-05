package pl.barometr.legislative.internal

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.DraftRecorded
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_CONTINUATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_MATCH_CANDIDATE
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Whether the government's draft and the print it became end up as one case, and by
 * what evidence.
 *
 * The thresholds are configuration, so the tests set them rather than depending on
 * where trigram similarity happens to land for one pair of Polish titles. What is
 * under test is the policy — which band a similarity falls into, and which bounds keep
 * a plausible wrong answer out — not the number pg_trgm returns.
 */
class DraftIdentityMatcherTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val drafts = DraftRepository(dsl, clock)
    private val identifiers = DraftIdentifierRepository(dsl, clock)
    private val continuations = DraftContinuationRepository(dsl, clock)
    private val candidates = DraftMatchCandidateRepository(dsl, clock)

    private lateinit var meters: SimpleMeterRegistry

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(DRAFT_MATCH_CANDIDATE).execute()
        dsl.deleteFrom(DRAFT_CONTINUATION).execute()
        dsl.deleteFrom(DRAFT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()
        meters = SimpleMeterRegistry()
    }

    /**
     * The join both registers make possible without anybody guessing: the Sejm prints
     * the number RPL filed the draft under, and nothing about the titles matters.
     */
    @Test
    fun `a number both registers quote joins the draft to its print`() {
        val government = governmentDraft(CARD_TITLE)
        identifiers.pointAtDraft(DraftIdentifierScheme.PROGRAMME_OF_WORK, "UD383", government, MatchMethod.EXACT, 1.0)
        val print = sejmDraft("Rządowy projekt ustawy o czymś zupełnie innym")
        identifiers.pointAtDraft(DraftIdentifierScheme.COUNCIL_OF_MINISTERS, "UD383", print, MatchMethod.EXACT, 1.0)

        matcher(joinsAbove = 0.99).joinDraftAcrossRegisters(DraftRecorded(print, clock.instant()))

        val continuation = assertNotNull(dsl.selectFrom(DRAFT_CONTINUATION).fetchOne())
        assertEquals(government.value, continuation.governmentDraftId)
        assertEquals(print.value, continuation.sejmDraftId)
        assertEquals(MatchMethod.EXACT.wireName, continuation.joinedBy)
        assertEquals(0, dsl.fetchCount(DRAFT_MATCH_CANDIDATE))
    }

    @Test
    fun `titles close enough are joined without asking anyone`() {
        val government = governmentDraft(CARD_TITLE)
        val print = sejmDraft(PRINT_TITLE)

        matcher(joinsAbove = 0.5).joinDraftAcrossRegisters(DraftRecorded(print, clock.instant()))

        val continuation = assertNotNull(dsl.selectFrom(DRAFT_CONTINUATION).fetchOne())
        assertEquals(government.value, continuation.governmentDraftId)
        assertEquals(MatchMethod.FUZZY.wireName, continuation.joinedBy)
        // The similarity is kept: a reader told these are one case is entitled to know
        // it was a guess, and how good a one.
        assertNotNull(continuation.confidence)
    }

    @Test
    fun `a plausible but uncertain pair is handed to a person`() {
        val government = governmentDraft(CARD_TITLE)
        val print = sejmDraft(PRINT_TITLE)

        matcher(joinsAbove = 0.99).joinDraftAcrossRegisters(DraftRecorded(print, clock.instant()))

        val candidate = assertNotNull(dsl.selectFrom(DRAFT_MATCH_CANDIDATE).fetchOne())
        assertEquals(government.value, candidate.governmentDraftId)
        assertEquals(print.value, candidate.sejmDraftId)
        assertEquals("pending", candidate.status)
        assertEquals(0, dsl.fetchCount(DRAFT_CONTINUATION), "nothing is joined until somebody says so")
    }

    /**
     * The rule that keeps the queue usable: most prints are not government drafts at
     * all, and queueing them would bury the decisions worth making.
     */
    @Test
    fun `a print with nothing like it in RPL is left alone`() {
        governmentDraft(CARD_TITLE)
        val print = sejmDraft(UNRELATED_TITLE)

        matcher(joinsAbove = 0.99).joinDraftAcrossRegisters(DraftRecorded(print, clock.instant()))

        assertEquals(0, dsl.fetchCount(DRAFT_CONTINUATION))
        assertEquals(0, dsl.fetchCount(DRAFT_MATCH_CANDIDATE))
    }

    /**
     * A ministry files near-identically titled amendments of the same act for years,
     * so the closest title is regularly one that cannot be the answer: a government
     * draft begun after the print was introduced is not what the print came from.
     */
    @Test
    fun `a government draft begun after the print is not what the print came from`() {
        governmentDraft(CARD_TITLE, startedOn = LocalDate.parse("2024-06-01"))
        val print = sejmDraft(PRINT_TITLE, startedOn = LocalDate.parse("2024-01-15"))

        matcher(joinsAbove = 0.5).joinDraftAcrossRegisters(DraftRecorded(print, clock.instant()))

        assertEquals(0, dsl.fetchCount(DRAFT_CONTINUATION))
        assertEquals(0, dsl.fetchCount(DRAFT_MATCH_CANDIDATE))
    }

    /**
     * RPL comes first in the world and second in this archive whenever the Sejm's
     * register was ingested before a backfill of RPL reached the same draft.
     */
    @Test
    fun `the card arriving second finds the print`() {
        val print = sejmDraft(PRINT_TITLE, startedOn = LocalDate.parse("2024-06-01"))
        val government = governmentDraft(CARD_TITLE, startedOn = LocalDate.parse("2024-01-15"))

        matcher(joinsAbove = 0.5).joinDraftAcrossRegisters(DraftRecorded(government, clock.instant()))

        val continuation = assertNotNull(dsl.selectFrom(DRAFT_CONTINUATION).fetchOne())
        assertEquals(government.value, continuation.governmentDraftId)
        assertEquals(print.value, continuation.sejmDraftId)
    }

    @Test
    fun `a redelivered event joins the same pair once`() {
        governmentDraft(CARD_TITLE)
        val print = sejmDraft(PRINT_TITLE)
        val matcher = matcher(joinsAbove = 0.5)

        matcher.joinDraftAcrossRegisters(DraftRecorded(print, clock.instant()))
        matcher.joinDraftAcrossRegisters(DraftRecorded(print, clock.instant()))

        assertEquals(1, dsl.fetchCount(DRAFT_CONTINUATION))
    }

    @Test
    fun `a redelivered event does not queue the same question twice`() {
        governmentDraft(CARD_TITLE)
        val print = sejmDraft(PRINT_TITLE)
        val matcher = matcher(joinsAbove = 0.99)

        matcher.joinDraftAcrossRegisters(DraftRecorded(print, clock.instant()))
        matcher.joinDraftAcrossRegisters(DraftRecorded(print, clock.instant()))

        assertEquals(1, dsl.fetchCount(DRAFT_MATCH_CANDIDATE))
    }

    /**
     * A draft already half of a join is spent. Without this a second, later print with
     * a similar title would take a government draft that already has one.
     */
    @Test
    fun `a government draft already joined is not offered to a second print`() {
        governmentDraft(CARD_TITLE)
        val first = sejmDraft(PRINT_TITLE)
        val second = sejmDraft(PRINT_TITLE)
        val matcher = matcher(joinsAbove = 0.5)

        matcher.joinDraftAcrossRegisters(DraftRecorded(first, clock.instant()))
        matcher.joinDraftAcrossRegisters(DraftRecorded(second, clock.instant()))

        assertEquals(1, dsl.fetchCount(DRAFT_CONTINUATION))
        assertEquals(0, dsl.fetchCount(DRAFT_MATCH_CANDIDATE))
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    private fun matcher(joinsAbove: Double) = DraftIdentityMatcher(
        drafts = drafts,
        continuations = continuations,
        candidates = candidates,
        properties = LegislativeProperties(automaticJoinAbove = joinsAbove, reviewJoinAbove = 0.35),
        meters = meters,
    )

    private fun governmentDraft(title: String, startedOn: LocalDate = CARD_CREATED_ON): DraftId {
        val id = drafts.insertDraft(
            DraftFromRegister(
                title = title,
                initiator = DraftInitiator.GOVERNMENT,
                term = 10,
                startedOn = startedOn,
            ),
        )
        identifiers.claimForDraft(DraftIdentifierScheme.RCL_PROJECT, "project-${id.value}", id)

        return id
    }

    private fun sejmDraft(title: String, startedOn: LocalDate = PRINT_STARTED_ON): DraftId {
        val id = drafts.insertDraft(
            DraftFromRegister(
                title = title,
                initiator = DraftInitiator.GOVERNMENT,
                term = 10,
                startedOn = startedOn,
            ),
        )
        identifiers.claimForDraft(DraftIdentifierScheme.SEJM_PRINT, "term10/print/${id.value}", id)

        return id
    }

    private companion object {
        /** How RPL writes it, and how the Sejm's register writes the same draft. */
        const val CARD_TITLE = "Projekt ustawy o zmianie ustawy o odnawialnych źródłach energii"
        const val PRINT_TITLE = "Rządowy projekt ustawy o zmianie ustawy o odnawialnych źródłach energii"
        const val UNRELATED_TITLE = "Poselski projekt uchwały w sprawie powołania komisji śledczej"

        val CARD_CREATED_ON: LocalDate = LocalDate.parse("2024-01-15")
        val PRINT_STARTED_ON: LocalDate = LocalDate.parse("2024-06-01")
    }
}
