package pl.barometr.audit.internal

import org.junit.jupiter.api.Test
import pl.barometr.audit.api.AuditOutcome
import pl.barometr.audit.api.AuditableAttempt
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserSessionsRevoked
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The entries no request explains.
 *
 * Everything else in this table arrives through the filter that records requests, and a
 * replayed refresh token ends every session an account has while leaving one refused
 * `POST` behind — which is what an expired token leaves too. This is the difference
 * between the two being recoverable and being lost.
 */
class SessionRevocationTrailTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val events = AuditEventRepository(dsl, clock)
    private val trail = SessionRevocationTrail(AuditTrailAdapter(events))
    private val integrity = ChainIntegrity(events)

    private val ewa = UserId.next()
    private val start = events.inChainOrder(0, MANY).lastOrNull()?.sequence ?: 0

    @Test
    fun `a family revoked for a replayed token is recorded, with the reason`() {
        trail.recordRevokedSessions(
            UserSessionsRevoked(ewa, UserSessionsRevoked.RevocationReason.TOKEN_REUSE_DETECTED, clock.instant()),
        )

        val entry = events.historyOf(ewa, MANY).single()
        assertEquals("REVOKE", entry.action)
        assertEquals("/api/v1/sessions", entry.resource)
        assertEquals(AuditOutcome.SUCCEEDED, entry.outcome)
        assertEquals("token_reuse_detected", entry.detail, "the answer to why somebody was signed out")
    }

    /** A session ended for going quiet is not a theft, and the trail says which it was. */
    @Test
    fun `each reason is recorded as itself`() {
        UserSessionsRevoked.RevocationReason.entries.forEach { reason ->
            trail.recordRevokedSessions(UserSessionsRevoked(ewa, reason, clock.instant()))
        }

        assertEquals(
            UserSessionsRevoked.RevocationReason.entries.map { it.name.lowercase() }.toSet(),
            events.historyOf(ewa, MANY).mapNotNull { it.detail }.toSet(),
        )
    }

    /**
     * The compatibility that matters: `detail` arrived after entries had been written,
     * and is hashed only where it is present. A chain carrying both shapes still
     * verifies, or the column would have cost the table the property it exists for.
     */
    @Test
    fun `a chain of entries with and without a reason still verifies`() {
        events.append(
            AuditableAttempt(
                actor = ewa,
                action = "POST",
                resource = "/api/v1/auth/refresh",
                outcome = AuditOutcome.DENIED,
                status = 401,
            ),
        )
        trail.recordRevokedSessions(
            UserSessionsRevoked(ewa, UserSessionsRevoked.RevocationReason.IDLE, clock.instant()),
        )
        events.append(
            AuditableAttempt(actor = ewa, action = "GET", resource = "/api/v1/me", outcome = AuditOutcome.SUCCEEDED),
        )

        assertTrue(integrity.verify(from = start).intact)
    }

    private companion object {
        const val MANY = 100
    }
}
