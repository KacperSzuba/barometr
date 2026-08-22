package pl.barometr.audit.internal

import org.junit.jupiter.api.Test
import pl.barometr.audit.api.AuditOutcome
import pl.barometr.audit.api.AuditableAttempt
import pl.barometr.identity.api.UserId
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The trail, against the schema — because the two guarantees being tested are the
 * database's rather than this code's: the table refuses to be changed, and the chain is
 * written in an order two concurrent appends cannot fork.
 */
class AuditEventRepositoryTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val events = AuditEventRepository(dsl, clock)

    private val ewa = UserId.next()

    /**
     * Where this test's own entries begin.
     *
     * There is no `@BeforeEach` clearing the table, because the table refuses to be
     * cleared — which is the point of it. JUnit builds an instance per method, so this
     * is the chain's end before this method appended anything, and every assertion
     * below reads from there.
     */
    private val start = events.inChainOrder(0, MANY).lastOrNull()?.sequence ?: 0

    @Test
    fun `every entry points at the one before it`() {
        val first = events.append(attempt(AuditOutcome.SUCCEEDED))
        val second = events.append(attempt(AuditOutcome.DENIED))
        val third = events.append(attempt(AuditOutcome.REJECTED))

        assertEquals(first.hash, second.previousHash)
        assertEquals(second.hash, third.previousHash)
    }

    /**
     * The entry an audit log is bought for. A denial is recorded with whoever attempted
     * it — including nobody, when the request carried no token at all.
     */
    @Test
    fun `a denial by an unauthenticated caller is recorded, actor and all`() {
        events.append(
            AuditableAttempt(
                actor = null,
                actorLabel = "anonymousUser",
                action = "GET",
                resource = "/api/v1/profiles/0198f0a1-0000-7000-8000-000000000001",
                outcome = AuditOutcome.DENIED,
                status = 401,
                peer = "10.0.0.7",
            ),
        )

        val recorded = events.inChainOrder(start, MANY).single()

        assertEquals(AuditOutcome.DENIED, recorded.outcome)
        assertNull(recorded.actor)
        assertEquals("anonymousUser", recorded.actorLabel)
        assertEquals("10.0.0.7", recorded.peer)
    }

    @Test
    fun `an account's own history comes back newest first`() {
        val marek = UserId.next()
        events.append(attempt(AuditOutcome.SUCCEEDED, actor = ewa))
        events.append(attempt(AuditOutcome.DENIED, actor = marek))
        events.append(attempt(AuditOutcome.SUCCEEDED, actor = ewa, resource = "/api/v1/profiles/2"))

        val hers = events.historyOf(ewa, 10)

        assertEquals(2, hers.size, "somebody else's entries are not hers")
        assertEquals("/api/v1/profiles/2", hers.first().resource)
    }

    /**
     * The specification's own acceptance criterion, and the reason a trigger was chosen
     * over a `REVOKE`: the application connects as the owner of this schema, and an
     * owner's privileges are its own to restore.
     */
    @Test
    fun `the table refuses to be changed, by whoever is connected`() {
        val entry = events.append(attempt(AuditOutcome.SUCCEEDED))

        val update = runCatching {
            dsl.execute("UPDATE audit.audit_event SET outcome = 'succeeded' WHERE sequence = ?", entry.sequence)
        }
        val delete = runCatching {
            dsl.execute("DELETE FROM audit.audit_event WHERE sequence = ?", entry.sequence)
        }
        val truncate = runCatching { dsl.execute("TRUNCATE audit.audit_event") }

        assertTrue(update.isFailure, "an audit entry must not be editable")
        assertTrue(delete.isFailure, "an audit entry must not be deletable")
        assertTrue(truncate.isFailure, "the trail must not be emptiable in one statement")
        assertEquals(1, events.inChainOrder(start, MANY).size, "and the entry is still there")
    }

    /**
     * Two appends racing must not both claim the same predecessor. A chain that forks
     * proves nothing, and the lock inside the append is what stops it.
     */
    @Test
    fun `concurrent appends produce one chain, not two`() {
        val threads = 8
        val workers = List(threads) {
            Thread { events.append(attempt(AuditOutcome.SUCCEEDED, resource = "/api/v1/profiles/$it")) }
        }

        workers.forEach { it.start() }
        workers.forEach { it.join() }

        val chain = events.inChainOrder(start, MANY)
        assertEquals(threads, chain.size)
        assertEquals(
            chain.dropLast(1).map { it.hash },
            chain.drop(1).map { it.previousHash },
            "every entry must point at exactly the one before it",
        )
    }

    private companion object {
        /** More than any one test writes, which is all this bound has to be. */
        const val MANY = 1000
    }

    private fun attempt(
        outcome: AuditOutcome,
        actor: UserId? = ewa,
        resource: String = "/api/v1/profiles/1",
    ) = AuditableAttempt(
        actor = actor,
        action = "POST",
        resource = resource,
        outcome = outcome,
        status = if (outcome == AuditOutcome.SUCCEEDED) 200 else 403,
        peer = "10.0.0.7",
    )
}
