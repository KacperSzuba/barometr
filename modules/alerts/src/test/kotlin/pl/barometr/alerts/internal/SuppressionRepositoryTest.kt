package pl.barometr.alerts.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.alerts.internal.jooq.tables.references.SUPPRESSED_ADDRESS
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The list nothing is ever sent to again.
 */
class SuppressionRepositoryTest {

    private val dsl = PostgresTestDatabase.dsl()
    private val suppressions = SuppressionRepository(dsl, TestClock())

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(SUPPRESSED_ADDRESS).execute()
    }

    /**
     * A provider reporting `Ewa@Example.COM` has to suppress the address held as
     * `ewa@example.com`, or the list quietly stops working while looking full.
     */
    @Test
    fun `an address is the same address in any case`() {
        suppressions.suppress("Ewa@Example.COM", SuppressionReason.BOUNCED)

        assertTrue(suppressions.suppresses("ewa@example.com"))
        assertTrue(suppressions.suppresses("  EWA@EXAMPLE.com "))
    }

    @Test
    fun `an address nobody reported is not suppressed`() {
        assertFalse(suppressions.suppresses("marek@example.com"))
    }

    /** Both stop the mail; the later reason is the one support will be asked about. */
    @Test
    fun `a bounce after an unsubscribe replaces the reason`() {
        suppressions.suppress("ewa@example.com", SuppressionReason.UNSUBSCRIBED)
        suppressions.suppress("ewa@example.com", SuppressionReason.BOUNCED, "550 no such user")

        assertEquals(SuppressionReason.BOUNCED, suppressions.reasonFor("ewa@example.com"))
        assertEquals(1, dsl.fetchCount(SUPPRESSED_ADDRESS))
    }

    /** A suppression somebody disputes is unarguable only if the provider's words survive. */
    @Test
    fun `what the provider said is kept`() {
        suppressions.suppress("ewa@example.com", SuppressionReason.BOUNCED, "550 5.1.1 unknown")

        val stored = dsl.selectFrom(SUPPRESSED_ADDRESS).fetchOne()!!
        assertEquals("550 5.1.1 unknown", stored.detail)
    }
}
