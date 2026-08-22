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
 * The beginning of the chain.
 *
 * One test, and a class of its own so that it has one — every class gets a database of
 * its own, and this is the only assertion in the suite that needs a table nothing has
 * ever written to. The table cannot be cleared to fake that, which is the point of it.
 */
class AuditChainGenesisTest {

    private val events = AuditEventRepository(PostgresTestDatabase.dslFor(javaClass), TestClock())

    @Test
    fun `the first entry ever written points at nothing, and the chain verifies from it`() {
        val first = events.append(
            AuditableAttempt(
                actor = UserId.next(),
                action = "POST",
                resource = "/api/v1/profiles",
                outcome = AuditOutcome.SUCCEEDED,
                status = 201,
            ),
        )

        assertNull(first.previousHash, "there is nothing before the first entry")

        val report = ChainIntegrity(events).verify()
        assertTrue(report.intact)
        assertEquals(1, report.checked)
    }
}
