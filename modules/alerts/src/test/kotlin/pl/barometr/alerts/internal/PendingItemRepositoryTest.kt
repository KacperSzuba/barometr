package pl.barometr.alerts.internal

import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.alerts.internal.jooq.tables.references.PENDING_ITEM
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The buffer between "something moved" and "somebody was told".
 */
class PendingItemRepositoryTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val pending = PendingItemRepository(dsl, clock)

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(PENDING_ITEM).execute()
    }

    /**
     * Everything that has stopped moving, which is what a run judges. The cut-off is
     * the clock's own instant, so anything recorded on this clock counts as settled —
     * the one test below moves the cut-off instead.
     */
    private fun settled() = pending.waiting(10, clock.instant())

    /**
     * Every crawl re-derives what it read, so one act restated four times a day arrives
     * here four times. Judging it four times would reach the same answer four times.
     */
    @Test
    fun `the same thing arriving twice while it waits is one item`() {
        assertTrue(pending.append(LegislativeKind.ACT, "a-1"))
        assertFalse(pending.append(LegislativeKind.ACT, "a-1"))

        assertEquals(1, settled().size)
    }

    /**
     * And the opposite, which matters as much: once judged, the same draft moving next
     * week is a new thing to decide about. Collapsing that would silence every later
     * stage of every draft anybody has ever been told about.
     */
    @Test
    fun `the same thing arriving after it was judged waits again`() {
        pending.append(LegislativeKind.DRAFT, "d-1")
        pending.markJudged(settled().single().id)

        clock.advanceBy(Duration.ofDays(7))

        assertTrue(pending.append(LegislativeKind.DRAFT, "d-1"))
        assertEquals(1, settled().size)
    }

    /**
     * The race this exists to lose. What a judgement reads — which industries an act
     * concerns, where a draft stands — is written by listeners on the same event that
     * buffered it, so an item judged the instant it lands is judged against whichever
     * of them finished first, marked judged, and never looked at again.
     */
    @Test
    fun `something that has only just arrived is not judged yet`() {
        pending.append(LegislativeKind.ACT, "a-1")

        assertEquals(0, pending.waiting(10, clock.instant().minus(Duration.ofMinutes(1))).size)
    }

    @Test
    fun `once it has stopped moving it is judged`() {
        pending.append(LegislativeKind.ACT, "a-1")
        clock.advanceBy(Duration.ofMinutes(2))

        assertEquals(1, pending.waiting(10, clock.instant().minus(Duration.ofMinutes(1))).size)
    }

    /**
     * `ck_pending_item_kind` and the constants that spell these are one fact in two
     * places. Tried rather than trusted, because a kind the database refuses would stop
     * a run mid-batch, and a kind nothing writes would be a silence nobody notices.
     */
    @Test
    fun `the buffer holds the three kinds this system judges, and nothing else`() {
        listOf(LegislativeKind.ACT, LegislativeKind.DRAFT, ConsultationNotice.KIND)
            .forEach { assertTrue(pending.append(it, "subject-1"), "$it is a thing that can wait here") }

        assertFailsWith<DataAccessException> { pending.append("proceeding", "subject-1") }
    }

    @Test
    fun `an act and a draft sharing an identifier are two things`() {
        pending.append(LegislativeKind.ACT, "1")
        pending.append(LegislativeKind.DRAFT, "1")

        assertEquals(2, settled().size)
    }

    @Test
    fun `what waits is what has not been judged, oldest first`() {
        pending.append(LegislativeKind.ACT, "a-1")
        clock.advanceBy(Duration.ofMinutes(5))
        pending.append(LegislativeKind.ACT, "a-2")

        assertEquals(listOf("a-1", "a-2"), settled().map { it.subjectId })

        pending.markJudged(settled().first().id)
        assertEquals(listOf("a-2"), settled().map { it.subjectId })
    }

    /** A judged row is kept: it is the evidence that a run saw the thing at all. */
    @Test
    fun `judging keeps the row`() {
        pending.append(LegislativeKind.ACT, "a-1")
        pending.markJudged(settled().single().id)

        assertEquals(1, dsl.fetchCount(PENDING_ITEM))
    }
}
