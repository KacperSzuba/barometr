package pl.barometr.alerts.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_DECISION
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_RULE
import pl.barometr.alerts.internal.jooq.tables.references.NOTIFICATION
import pl.barometr.identity.api.UserId
import pl.barometr.legislative.api.LegislativeSignals
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The promise the whole context exists for: one notification about a matter, not
 * eight — and a written answer for every time it said nothing.
 */
class AlertRaiserTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val rules = AlertRuleRepository(dsl, clock)
    private val notifications = NotificationRepository(dsl, clock)
    private val decisions = AlertDecisionRepository(dsl, clock)
    private val matching = FakeMatching()
    private val raiser = AlertRaiser(matching, SignificanceScale(clock), rules, notifications, decisions, clock)

    private val ewa = UserId.next()
    private val profile = ProfileId(Ids.next())

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(ALERT_DECISION).execute()
        dsl.deleteFrom(NOTIFICATION).execute()
        dsl.deleteFrom(ALERT_RULE).execute()
    }

    @Test
    fun `somebody whose profile catches an act, and who asked to be told, is told`() {
        rules.create(ewa, profile, stages = emptySet())
        matching.catches("a-1", profile, ewa, InterestKind.ACT, "DU/2024/1222")

        assertEquals(1, raiser.raiseFor(act("a-1")))

        val told = notifications.listFor(ewa, 10).single()
        assertEquals("Prawo budowlane", told.title)
        assertEquals("DU/2024/1222", told.matchedBy.value)
        assertEquals(reasons(), listOf("matched"))
    }

    /**
     * A profile is an interest, not a subscription. Somebody who described what they
     * care about and never asked to be woken for it is not woken for it.
     */
    @Test
    fun `a profile with no rule is matched and left alone`() {
        matching.catches("a-1", profile, ewa)

        assertEquals(0, raiser.raiseFor(act("a-1")))
        assertEquals(reasons(), listOf("no_rule"))
    }

    @Test
    fun `a disabled rule is a written decision, not silence`() {
        val rule = rules.create(ewa, profile, emptySet())!!
        rules.update(rule.copy(enabled = false))
        matching.catches("a-1", profile, ewa)

        assertEquals(0, raiser.raiseFor(act("a-1")))
        assertEquals(reasons(), listOf("rule_disabled"))
    }

    @Test
    fun `a draft at a stage the rule does not watch is withheld`() {
        rules.create(ewa, profile, stages = setOf("senate"))
        matching.catches("d-1", profile, ewa)

        assertEquals(0, raiser.raiseFor(draft("d-1", stage = "committee_work")))
        assertEquals(reasons(), listOf("stage_not_watched"))
    }

    /**
     * A stage is where a draft stands; an act has arrived. Somebody who asked to hear
     * from the Senate onwards wanted to be spared the early noise, not to miss the law
     * being published.
     */
    @Test
    fun `a published act passes a stage filter`() {
        rules.create(ewa, profile, stages = setOf("senate"))
        matching.catches("a-1", profile, ewa)

        assertEquals(1, raiser.raiseFor(act("a-1")))
    }

    /**
     * "Only the important ones" is a sentence about a rule, not about a profile: what
     * somebody cares about has not changed, only what they want waking up for.
     */
    @Test
    fun `a rule that asked for only the important ones is not woken by the rest`() {
        rules.create(ewa, profile, stages = emptySet(), minimumSignificance = 90)
        matching.catches("a-1", profile, ewa)

        assertEquals(0, raiser.raiseFor(act("a-1")))
        assertEquals(listOf("below_threshold"), reasons())
    }

    @Test
    fun `the same rule is woken by something that clears the bar`() {
        rules.create(ewa, profile, stages = emptySet(), minimumSignificance = 70)
        // Published, on a watchlist by name, and a fortnight from applying: about as
        // much as anything in this system is ever worth.
        matching.catches("a-1", profile, ewa, kind = InterestKind.ACT, value = "DU/2024/1222")

        assertEquals(1, raiser.raiseFor(act("a-1", inForce = Duration.ofDays(14))))
    }

    /**
     * Frozen at the moment of the decision. A list ordered by a number that moved under
     * it would reshuffle every time it was opened, and last Tuesday's notification would
     * be ranked by where the draft stands this Thursday.
     */
    @Test
    fun `what somebody was told records how much it mattered, and why`() {
        rules.create(ewa, profile, emptySet())
        matching.catches("a-1", profile, ewa)

        raiser.raiseFor(act("a-1", inForce = Duration.ofDays(3)))

        val told = notifications.listFor(ewa, 10).single()
        assertTrue(told.significance.score > 0)
        assertEquals(
            listOf(SignificanceReason.IN_FORCE, SignificanceReason.DEADLINE_IMMINENT),
            told.significance.reasons,
        )
    }

    /**
     * The register restates acts it published long ago on every crawl. Each restatement
     * arrives here as the same news, and the same news is told once.
     */
    @Test
    fun `the same news twice reaches somebody once`() {
        rules.create(ewa, profile, emptySet())
        matching.catches("a-1", profile, ewa)

        raiser.raiseFor(act("a-1"))
        clock.advanceBy(Duration.ofDays(3))
        raiser.raiseFor(act("a-1"))

        assertEquals(1, notifications.listFor(ewa, 10).size)
        assertEquals(reasons(), listOf("matched", "already_told"))
    }

    /**
     * A draft can move twice between breakfast and lunch. Both are news; hearing about
     * both is hearing about neither.
     */
    @Test
    fun `two pieces of news about one draft in a day are one notification`() {
        rules.create(ewa, profile, emptySet())
        matching.catches("d-1", profile, ewa)

        raiser.raiseFor(draft("d-1", stage = "i_reading"))
        clock.advanceBy(Duration.ofHours(3))
        raiser.raiseFor(draft("d-1", stage = "committee_work"))

        assertEquals(1, notifications.listFor(ewa, 10).size)
        assertEquals(reasons(), listOf("matched", "case_recently_raised"))
    }

    @Test
    fun `the same draft moving again the next day is news again`() {
        rules.create(ewa, profile, emptySet())
        matching.catches("d-1", profile, ewa)

        raiser.raiseFor(draft("d-1", stage = "i_reading"))
        clock.advanceBy(Duration.ofHours(25))
        raiser.raiseFor(draft("d-1", stage = "committee_work"))

        assertEquals(2, notifications.listFor(ewa, 10).size)
    }

    /**
     * "You watch this act" is a better answer to "why am I being told this" than "you
     * watch the word *ustawa*" — and it is the one the reader is least likely to want
     * switched off.
     */
    @Test
    fun `a profile caught by two interests is told once, citing the more specific`() {
        rules.create(ewa, profile, emptySet())
        matching.catches("a-1", profile, ewa, InterestKind.KEYWORD, "prawo")
        matching.catches("a-1", profile, ewa, InterestKind.ACT, "DU/2024/1222")

        assertEquals(1, raiser.raiseFor(act("a-1")))
        assertEquals(InterestKind.ACT, notifications.listFor(ewa, 10).single().matchedBy.kind)
    }

    @Test
    fun `two people watching the same act are both told`() {
        val marek = UserId.next()
        val other = ProfileId(Ids.next())
        rules.create(ewa, profile, emptySet())
        rules.create(marek, other, emptySet())
        matching.catches("a-1", profile, ewa)
        matching.catches("a-1", other, marek)

        assertEquals(2, raiser.raiseFor(act("a-1")))
        assertEquals(1, notifications.listFor(marek, 10).size)
    }

    @Test
    fun `the notification cites the profile version that matched`() {
        rules.create(ewa, profile, emptySet())
        matching.catches("a-1", profile, ewa, version = 7)

        raiser.raiseFor(act("a-1"))

        assertEquals(7, notifications.listFor(ewa, 10).single().profileVersion)
    }

    @Test
    fun `nobody interested is nobody told, and nothing to explain`() {
        assertEquals(0, raiser.raiseFor(act("a-9")))
        assertTrue(decisions.listFor(ewa, 10).isEmpty())
    }

    /**
     * An act is at the end of the path by definition, so [inForce] is the only thing
     * left that changes what it is worth.
     */
    private fun act(id: String, inForce: Duration? = null) = ResolvedItem(
        kind = "act",
        id = id,
        title = "Prawo budowlane",
        eli = "DU/2024/1222",
        stage = null,
        signals = LegislativeSignals(1.0, inForce?.let { clock.instant().plus(it) }),
    )

    private fun draft(id: String, stage: String) =
        ResolvedItem("draft", id, "Projekt ustawy Prawo budowlane", eli = null, stage = stage, signals = null)

    /** Decisions oldest first, which is the order they were taken in. */
    private fun reasons() = decisions.listFor(ewa, 10).map { it.reason }.reversed()
}
