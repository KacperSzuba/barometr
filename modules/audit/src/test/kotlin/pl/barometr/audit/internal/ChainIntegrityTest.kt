package pl.barometr.audit.internal

import org.junit.jupiter.api.Test
import pl.barometr.audit.api.AuditOutcome
import pl.barometr.audit.api.AuditableAttempt
import pl.barometr.identity.api.UserId
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Whether the chain would actually catch somebody.
 *
 * The table refuses to be changed by the application, so tampering is simulated the way
 * it would really happen: by dropping the trigger first, which is what an attacker with
 * database access would do. If the chain cannot survive that, it is decoration.
 *
 * The entry that points at nothing — the first ever written — is asserted in
 * [AuditChainGenesisTest], which has a database to itself for exactly that reason.
 */
class ChainIntegrityTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val events = AuditEventRepository(dsl, TestClock())
    private val integrity = ChainIntegrity(events)

    /**
     * Where this method's own entries begin. The table refuses to be cleared — that is
     * the point of it — and JUnit builds an instance per method, so each verifies the
     * slice it wrote rather than what the class wrote before it.
     */
    private val start = events.inChainOrder(0, MANY).lastOrNull()?.sequence ?: 0

    @Test
    fun `an untouched chain verifies`() {
        events.append(attempt("/api/v1/profiles"))
        events.append(attempt("/api/v1/alerts/rules"))
        events.append(attempt("/api/v1/profiles/1", AuditOutcome.DENIED))

        val report = integrity.verify(start)

        assertTrue(report.intact)
        assertEquals(3, report.checked)
        assertNull(report.brokenAt)
    }

    /**
     * The attack the chain exists for: somebody with database access edits an entry to
     * make a denial look like a success.
     */
    @Test
    fun `an entry changed in place is caught, and named`() {
        events.append(attempt("/api/v1/profiles"))
        val denial = events.append(attempt("/api/v1/profiles/1", AuditOutcome.DENIED))
        events.append(attempt("/api/v1/alerts/rules"))

        withoutTheTrigger {
            dsl.execute(
                "UPDATE audit.audit_event SET outcome = 'succeeded' WHERE sequence = ?",
                denial.sequence,
            )
        }

        val report = integrity.verify(start)

        assertFalse(report.intact)
        assertEquals(denial.sequence, report.brokenAt)
        assertTrue(report.why!!.contains("fields were changed"), report.why!!)
    }

    /** The other attack: the entry is not edited, it is removed. */
    @Test
    fun `an entry removed from the middle is caught at the one after it`() {
        events.append(attempt("/api/v1/profiles"))
        val removed = events.append(attempt("/api/v1/profiles/1", AuditOutcome.DENIED))
        val after = events.append(attempt("/api/v1/alerts/rules"))

        withoutTheTrigger {
            dsl.execute("DELETE FROM audit.audit_event WHERE sequence = ?", removed.sequence)
        }

        val report = integrity.verify(start)

        assertFalse(report.intact)
        assertEquals(after.sequence, report.brokenAt, "the gap shows at the entry that survived it")
        assertTrue(report.why!!.contains("removed or inserted"), report.why!!)
    }

    /**
     * The trigger is the application's guarantee; this is what an attacker who is past
     * it would have to do. Dropped and restored, because everything after this in the
     * class still expects the table to defend itself.
     */
    private fun withoutTheTrigger(tamper: () -> Unit) {
        dsl.execute("ALTER TABLE audit.audit_event DISABLE TRIGGER audit_event_is_append_only")
        try {
            tamper()
        } finally {
            dsl.execute("ALTER TABLE audit.audit_event ENABLE TRIGGER audit_event_is_append_only")
        }
    }

    private companion object {
        /** More than any one test writes, which is all this bound has to be. */
        const val MANY = 1000
    }

    private fun attempt(resource: String, outcome: AuditOutcome = AuditOutcome.SUCCEEDED) =
        AuditableAttempt(
            actor = UserId.next(),
            action = "POST",
            resource = resource,
            outcome = outcome,
            status = if (outcome == AuditOutcome.SUCCEEDED) 201 else 403,
            peer = "10.0.0.7",
        )
}
