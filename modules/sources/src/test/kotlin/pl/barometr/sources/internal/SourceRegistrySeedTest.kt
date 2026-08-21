package pl.barometr.sources.internal

import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import pl.barometr.sources.internal.jooq.tables.references.SOURCE
import pl.barometr.testing.PostgresTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The deployment guard, checked against a real database rather than trusted.
 *
 * The spec's F0 requirement is that no source reaches production without a
 * recorded legal basis. That is expressed as a `CHECK` constraint rather than as a
 * code path precisely so it cannot be forgotten when someone adds a connector —
 * and a constraint nobody ever tried to violate is a constraint nobody knows works.
 */
class SourceRegistrySeedTest {

    private val dsl = PostgresTestDatabase.dsl()

    @Test
    fun `the Sejm source is seeded enabled with its legal basis`() {
        val sejm = assertNotNull(dsl.selectFrom(SOURCE).where(SOURCE.CONNECTOR_ID.eq("sejm")).fetchOne())

        assertTrue(sejm.enabled!!)
        assertTrue(sejm.legalBasis!!.contains("dostępie do informacji publicznej"))
    }

    /**
     * ISAP closes the path the other two open, and is seeded enabled for a reason
     * that is a fact rather than a judgement: journals of law are published for
     * everyone by statute, through the Chancellery's own API.
     */
    @Test
    fun `the ISAP source is seeded enabled with its legal basis`() {
        val isap = assertNotNull(dsl.selectFrom(SOURCE).where(SOURCE.CONNECTOR_ID.eq("isap")).fetchOne())

        assertTrue(isap.enabled!!)
        assertTrue(isap.legalBasis!!.contains("ogłaszaniu aktów normatywnych"))
        // The ELI prefix belongs to the registry row, not to the client: the Sejm API
        // is served from the same host, and splitting the path between configuration
        // and code is how two connectors end up disagreeing about where a source is.
        assertEquals("https://api.sejm.gov.pl/eli", isap.baseUrl)
    }

    /**
     * RCL is seeded so that its pace and identity are registry data, and left off
     * because reading a site whose robots.txt disallows everything is a decision
     * for a person, not for a migration.
     */
    @Test
    fun `the RCL source is seeded disabled and without a legal basis`() {
        val rcl = assertNotNull(dsl.selectFrom(SOURCE).where(SOURCE.CONNECTOR_ID.eq("rcl")).fetchOne())

        assertEquals("https://legislacja.rcl.gov.pl", rcl.baseUrl)
        assertFalse(rcl.enabled!!)
        assertNull(rcl.legalBasis)
    }

    @Test
    fun `enabling a source without a legal basis is refused by the database`() {
        val failure = runCatching {
            dsl.update(SOURCE)
                .set(SOURCE.ENABLED, true)
                .where(SOURCE.CONNECTOR_ID.eq("rcl"))
                .execute()
        }.exceptionOrNull()

        assertNotNull(failure, "the database must refuse to enable a source with no legal basis")
        assertTrue(failure.toString().contains("ck_source_legal_basis_before_enabling"))
    }

    /**
     * The same row becomes enableable the moment a basis is written down — so the
     * constraint is a gate, not a wall.
     *
     * Done inside a transaction that is then rolled back, which is why the throw
     * below is caught rather than allowed to fail the test: jOOQ rolls back on any
     * exception escaping the block and rethrows it, and the rollback is the point.
     */
    @Test
    fun `a source with a recorded basis can be enabled`() {
        var enabledInsideTransaction = false

        runCatching {
            dsl.transaction { config ->
                val tx = DSL.using(config)
                tx.update(SOURCE)
                    .set(SOURCE.LEGAL_BASIS, "Ustawa z 6.09.2001 o dostępie do informacji publicznej")
                    .set(SOURCE.ENABLED, true)
                    .where(SOURCE.CONNECTOR_ID.eq("rcl"))
                    .execute()

                enabledInsideTransaction =
                    tx.selectFrom(SOURCE).where(SOURCE.CONNECTOR_ID.eq("rcl")).fetchOne()!!.enabled!!
                throw RollbackAfterAssertion()
            }
        }

        assertTrue(enabledInsideTransaction, "a recorded basis must satisfy the constraint")
        // And the registry is left exactly as the migration wrote it.
        val rcl = assertNotNull(dsl.selectFrom(SOURCE).where(SOURCE.CONNECTOR_ID.eq("rcl")).fetchOne())
        assertFalse(rcl.enabled!!)
        assertNull(rcl.legalBasis)
    }

    private class RollbackAfterAssertion : RuntimeException()
}
