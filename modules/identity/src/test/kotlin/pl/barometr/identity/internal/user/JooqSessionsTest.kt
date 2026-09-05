package pl.barometr.identity.internal.user

import org.jooq.exception.DataAccessException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.identity.internal.jooq.tables.references.SESSION
import pl.barometr.identity.internal.jooq.tables.references.USERS
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session table against a real Postgres, because two of the things being checked
 * are the database's: an address is stored as an `inet` rather than as whatever text
 * arrived, and a session ends when the account does.
 */
class JooqSessionsTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val sessions = JooqSessions(dsl)

    private lateinit var ewa: UUID
    private lateinit var marek: UUID

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(USERS).execute()
        ewa = user("ewa@example.test")
        marek = user("marek@example.test")
    }

    @Test
    fun `a session comes back as it was opened, address included`() {
        val family = open(ewa, userAgent = "Mozilla/5.0 (Macintosh)", clientIp = "203.0.113.7")

        val stored = assertNotNull(sessions.byFamily(family))

        assertEquals(ewa, stored.userId)
        assertEquals("Mozilla/5.0 (Macintosh)", stored.userAgent)
        assertEquals("203.0.113.7", stored.clientIp)
        assertNull(stored.revokedAt)
    }

    @Test
    fun `an IPv6 address survives the round trip`() {
        val family = open(ewa, clientIp = "2001:db8::7")

        assertEquals("2001:db8:0:0:0:0:0:7", sessions.byFamily(family)?.clientIp)
    }

    /**
     * A header nobody validated is not worth failing a login over, and it is certainly
     * not worth a DNS lookup: an address that is not one simply is not recorded.
     */
    @Test
    fun `something that is not an address is left out rather than resolved`() {
        val family = open(ewa, clientIp = "nie-jest-adresem.example.test")

        assertNull(sessions.byFamily(family)?.clientIp)
    }

    @Test
    fun `a second token in the same family is the same session`() {
        val family = open(ewa)
        clock.advanceBy(Duration.ofMinutes(5))
        open(ewa, family = family)

        assertEquals(1, sessions.liveFor(ewa).size, "one family is one session")
        assertEquals(clock.instant(), sessions.byFamily(family)?.lastSeenAt)
    }

    @Test
    fun `only live sessions are listed, and only this account's`() {
        val kept = open(ewa)
        val ended = open(ewa)
        open(marek)

        sessions.revoke(ended, clock.instant())

        assertEquals(listOf(kept), sessions.liveFor(ewa).map { it.familyId })
    }

    @Test
    fun `revoking says whether there was anything to revoke`() {
        val family = open(ewa)

        assertTrue(sessions.revoke(family, clock.instant()))
        assertTrue(!sessions.revoke(family, clock.instant()), "a second revocation changes nothing")
    }

    @Test
    fun `everything except one can be ended in a single statement`() {
        val here = open(ewa)
        val laptop = open(ewa)
        val phone = open(ewa)
        val theirs = open(marek)

        val ended = sessions.revokeAllExcept(ewa, here, clock.instant())

        assertEquals(setOf(laptop, phone), ended.toSet())
        assertEquals(listOf(here), sessions.liveFor(ewa).map { it.familyId })
        assertEquals(listOf(theirs), sessions.liveFor(marek).map { it.familyId })
    }

    /** Personal data kept to secure an account goes when the account does. */
    @Test
    fun `deleting the account takes its sessions with it`() {
        val family = open(ewa)

        dsl.deleteFrom(USERS).where(USERS.ID.eq(ewa)).execute()

        assertNull(sessions.byFamily(family))
    }

    /** The column is a record of what a client said, not storage for whatever it sends. */
    @Test
    fun `a user agent longer than the column allows is refused by the schema`() {
        assertFailsWith<DataAccessException> {
            dsl.insertInto(SESSION)
                .set(SESSION.FAMILY_ID, Ids.next())
                .set(SESSION.USER_ID, ewa)
                .set(SESSION.USER_AGENT, "M".repeat(401))
                .set(SESSION.CREATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .set(SESSION.LAST_SEEN_AT, clock.instant().atOffset(ZoneOffset.UTC))
                .execute()
        }
    }

    private fun open(
        user: UUID,
        family: UUID = Ids.next(),
        userAgent: String? = "Mozilla/5.0",
        clientIp: String? = "203.0.113.7",
    ): UUID {
        sessions.open(
            SignedInSession(
                familyId = family,
                userId = user,
                userAgent = userAgent,
                clientIp = clientIp,
                createdAt = clock.instant(),
                lastSeenAt = clock.instant(),
            ),
        )
        return family
    }

    private fun user(email: String): UUID {
        val id = Ids.next()
        dsl.insertInto(USERS)
            .set(USERS.ID, id)
            .set(USERS.EMAIL, email)
            .set(USERS.PASSWORD_HASH, "not-a-hash")
            .set(USERS.ENABLED, true)
            .set(USERS.CREATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .execute()
        return id
    }
}
