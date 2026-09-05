package pl.barometr.identity.internal.privacy

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.shared.ErasureReport
import pl.barometr.shared.Ids
import pl.barometr.shared.PersonalDataExtract
import pl.barometr.shared.PersonalDataStore
import pl.barometr.shared.PersonalDataTable
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Closing an account, everywhere at once.
 *
 * What is under test is the orchestration rather than any context's own deletion: that
 * every store on the classpath is asked, that identity goes last, that a failure anywhere
 * takes the whole thing down rather than leaving half an account, and that what a context
 * kept is reported rather than swallowed.
 */
class AccountErasureTest {

    private val ewa = UserId(Ids.next())

    @Test
    fun `every context that holds anything is asked, without being named here`() {
        val profiles = RecordingStore("profiles", deleted = mapOf("interest_profile" to 2))
        val alerts = RecordingStore("alerts", deleted = mapOf("notification" to 14))

        val reports = erasure(profiles, alerts).eraseAccount(ewa)

        assertEquals(listOf(ewa.value), profiles.erased)
        assertEquals(listOf(ewa.value), alerts.erased)
        assertEquals(16, reports.sumOf { it.rowsDeleted })
    }

    /**
     * Identity holds the account row every other context's data is about, so it goes
     * last: a store working out what it holds about somebody should still be able to read
     * who they are.
     */
    @Test
    fun `identity is erased after everything else`() {
        val order = mutableListOf<String>()
        val identity = RecordingStore("identity", order = order)
        val alerts = RecordingStore("alerts", order = order)
        val profiles = RecordingStore("profiles", order = order)

        erasure(identity, alerts, profiles).eraseAccount(ewa)

        assertEquals("identity", order.last())
    }

    /** Half a deletion is worse than either outcome: an account that cannot sign in, and data still there. */
    @Test
    fun `a context that fails takes the whole erasure down with it`() {
        val refuses = object : PersonalDataStore {
            override val category = "alerts"

            override fun personalDataOf(user: UUID) = PersonalDataExtract(category, emptyList())

            override fun erasePersonalData(user: UUID): ErasureReport = error("the database is on fire")
        }

        assertFailsWith<IllegalStateException> { erasure(RecordingStore("profiles"), refuses).eraseAccount(ewa) }
    }

    @Test
    fun `what a context kept is reported rather than swallowed`() {
        val audit = RecordingStore(
            "audit",
            deleted = emptyMap(),
            kept = mapOf("audit_event" to "append-only and hash-chained"),
        )

        val reports = erasure(audit).eraseAccount(ewa)

        assertEquals(0, reports.single().rowsDeleted)
        assertTrue(reports.single().kept.containsKey("audit_event"))
    }

    private fun erasure(vararg stores: PersonalDataStore) = AccountErasure(stores.toList(), SimpleMeterRegistry())

    /** A context that holds something, as much of one as an orchestration test needs. */
    private class RecordingStore(
        override val category: String,
        private val deleted: Map<String, Int> = emptyMap(),
        private val kept: Map<String, String> = emptyMap(),
        private val order: MutableList<String> = mutableListOf(),
    ) : PersonalDataStore {
        val erased = mutableListOf<UUID>()

        override fun personalDataOf(user: UUID) =
            PersonalDataExtract(category, listOf(PersonalDataTable(category, emptyList())))

        override fun erasePersonalData(user: UUID): ErasureReport {
            erased += user
            order += category
            return ErasureReport(category, deleted, kept)
        }
    }
}
