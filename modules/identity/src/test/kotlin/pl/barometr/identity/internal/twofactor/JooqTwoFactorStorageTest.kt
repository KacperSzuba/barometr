package pl.barometr.identity.internal.twofactor

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.encrypt.Encryptors
import pl.barometr.identity.internal.jooq.tables.references.LOGIN_CHALLENGE
import pl.barometr.identity.internal.jooq.tables.references.RECOVERY_CODE
import pl.barometr.identity.internal.jooq.tables.references.TOTP_SECRET
import pl.barometr.identity.internal.jooq.tables.references.USERS
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three tables behind the second factor, against a real Postgres.
 *
 * The claim being checked here is the one the schema comment makes: a database dump does
 * not yield working second factors. Everything else — single use, cascade, the attempt
 * counter surviving — is the database's job too, and none of it is provable against a map.
 */
class JooqTwoFactorStorageTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val encryptor = Encryptors.delux("a-development-key", "5c0744940b5c369b")

    private val secrets = JooqTwoFactorSecrets(dsl, encryptor)
    private val recoveryCodes = JooqRecoveryCodes(dsl)
    private val challenges = JooqLoginChallenges(dsl)

    private lateinit var ewa: UUID

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(USERS).execute()
        ewa = user("ewa@example.test")
    }

    @Test
    fun `the secret comes back as it went in, and is not what the row holds`() {
        secrets.save(EnrolledSecret(ewa, SECRET, confirmedAt = null, createdAt = clock.instant()))

        assertEquals(SECRET, secrets.forUser(ewa)?.secret)

        val stored = dsl.select(TOTP_SECRET.SECRET).from(TOTP_SECRET).fetchOne()?.value1()
        assertNotNull(stored)
        assertFalse(stored.contains(SECRET), "a dump of this column is not a set of second factors")
    }

    @Test
    fun `a secret is confirmed once`() {
        secrets.save(EnrolledSecret(ewa, SECRET, confirmedAt = null, createdAt = clock.instant()))

        assertTrue(secrets.confirm(ewa, clock.instant()))
        assertFalse(secrets.confirm(ewa, clock.instant()), "a second confirmation changes nothing")
        assertTrue(secrets.forUser(ewa)!!.isConfirmed)
    }

    @Test
    fun `setting up again replaces what was there, confirmation included`() {
        secrets.save(EnrolledSecret(ewa, SECRET, confirmedAt = clock.instant(), createdAt = clock.instant()))

        secrets.save(EnrolledSecret(ewa, "JBSWY3DPEHPK3PXQ", confirmedAt = null, createdAt = clock.instant()))

        val stored = assertNotNull(secrets.forUser(ewa))
        assertEquals("JBSWY3DPEHPK3PXQ", stored.secret)
        assertFalse(stored.isConfirmed)
    }

    @Test
    fun `a recovery code is spent exactly once`() {
        recoveryCodes.replaceAll(ewa, listOf("a".repeat(64), "b".repeat(64)), clock.instant())

        assertTrue(recoveryCodes.consume(ewa, "a".repeat(64), clock.instant()))
        assertFalse(recoveryCodes.consume(ewa, "a".repeat(64), clock.instant()))
        assertEquals(1, recoveryCodes.unusedCount(ewa))
    }

    @Test
    fun `a spent code stays on the record rather than disappearing`() {
        recoveryCodes.replaceAll(ewa, listOf("a".repeat(64)), clock.instant())
        recoveryCodes.consume(ewa, "a".repeat(64), clock.instant())

        assertEquals(1, dsl.fetchCount(RECOVERY_CODE), "when a code was used is what an investigation needs")
    }

    @Test
    fun `minting a new set retires the old one`() {
        recoveryCodes.replaceAll(ewa, listOf("a".repeat(64)), clock.instant())
        recoveryCodes.replaceAll(ewa, listOf("b".repeat(64)), clock.instant())

        assertFalse(recoveryCodes.consume(ewa, "a".repeat(64), clock.instant()))
        assertEquals(1, recoveryCodes.unusedCount(ewa))
    }

    @Test
    fun `the attempt counter survives, because the next attempt may be answered elsewhere`() {
        val challenge = openChallenge()

        assertEquals(1, challenges.recordAttempt(challenge.id))
        assertEquals(2, challenges.recordAttempt(challenge.id))
        assertEquals(2, challenges.byIdForUpdate(challenge.id)?.attempts)
    }

    @Test
    fun `a challenge is consumed once`() {
        val challenge = openChallenge()

        assertTrue(challenges.consume(challenge.id, clock.instant()))
        assertFalse(challenges.consume(challenge.id, clock.instant()))
    }

    @Test
    fun `expired challenges are swept, and live ones are left alone`() {
        val stale = openChallenge(ttl = Duration.ofMinutes(1))
        val live = openChallenge(ttl = Duration.ofMinutes(30))

        clock.advanceBy(Duration.ofMinutes(5))

        assertEquals(1, challenges.deleteFinishedBefore(clock.instant()))
        assertNull(challenges.byIdForUpdate(stale.id))
        assertNotNull(challenges.byIdForUpdate(live.id))
    }

    /** Every trace of a second factor goes when the account does. */
    @Test
    fun `deleting the account takes the secret, the codes and the challenges with it`() {
        secrets.save(EnrolledSecret(ewa, SECRET, confirmedAt = clock.instant(), createdAt = clock.instant()))
        recoveryCodes.replaceAll(ewa, listOf("a".repeat(64)), clock.instant())
        openChallenge()

        dsl.deleteFrom(USERS).where(USERS.ID.eq(ewa)).execute()

        assertEquals(0, dsl.fetchCount(TOTP_SECRET))
        assertEquals(0, dsl.fetchCount(RECOVERY_CODE))
        assertEquals(0, dsl.fetchCount(LOGIN_CHALLENGE))
    }

    private fun openChallenge(ttl: Duration = Duration.ofMinutes(5)): LoginChallenge =
        challenges.open(
            LoginChallenge(
                id = Ids.next(),
                userId = ewa,
                expiresAt = clock.instant().plus(ttl),
                consumedAt = null,
                attempts = 0,
                createdAt = clock.instant(),
            ),
        )

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

    private companion object {
        /** A base32 secret of the size RFC 4226 recommends. */
        const val SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
    }
}
