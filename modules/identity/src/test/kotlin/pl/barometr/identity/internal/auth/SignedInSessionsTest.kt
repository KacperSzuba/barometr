package pl.barometr.identity.internal.auth

import org.junit.jupiter.api.Test
import pl.barometr.identity.api.SignedInFromNewDevice
import pl.barometr.identity.api.UserId
import pl.barometr.identity.api.UserSessionsRevoked
import pl.barometr.identity.internal.user.InMemoryRefreshTokens
import pl.barometr.identity.internal.user.InMemorySessions
import pl.barometr.identity.internal.user.RefreshToken
import pl.barometr.identity.internal.user.UnknownLocations
import pl.barometr.shared.Ids
import pl.barometr.testing.TestClock
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The device list, and ending a device from somewhere else.
 *
 * What matters here is that ending a session ends the *tokens* with it: a row marked
 * revoked while the refresh family lives on would be a list that lies, and the lie
 * would only surface when the device somebody thought they had signed out kept working.
 */
class SignedInSessionsTest {

    private val clock = TestClock()
    private val sessions = InMemorySessions()
    private val tokens = InMemoryRefreshTokens()
    private val events = RecordingEvents()

    private val service = SignedInSessions(sessions, UnknownLocations, tokens, events, clock)

    private val ewa = UserId(Ids.next())
    private val marek = UserId(Ids.next())

    @Test
    fun `a login shows up as a device, with what it was made from`() {
        val signedInAt = clock.instant()
        val laptop = signIn(ewa, "Mozilla/5.0 (Macintosh)", "203.0.113.7")

        val listed = service.sessionsOf(ewa).single()

        assertEquals(laptop, listed.familyId)
        assertEquals("Mozilla/5.0 (Macintosh)", listed.userAgent)
        assertEquals("203.0.113.7", listed.clientIp)
        assertEquals(signedInAt, listed.lastSeenAt)
    }

    @Test
    fun `the devices are listed most recently seen first`() {
        val older = signIn(ewa)
        val newer = signIn(ewa)

        assertEquals(listOf(newer, older), service.sessionsOf(ewa).map { it.familyId })
    }

    @Test
    fun `ending a session revokes every token of that login`() {
        val laptop = signIn(ewa)
        val phone = signIn(ewa)

        service.endSession(ewa, laptop)

        assertEquals(listOf(phone), service.sessionsOf(ewa).map { it.familyId })
        assertTrue(tokens.live().none { it.familyId == laptop }, "the tokens go with the session")
        assertTrue(tokens.live().any { it.familyId == phone }, "and nothing else is touched")
    }

    @Test
    fun `ending a session says why, so an audit can tell it from a logout`() {
        service.endSession(ewa, signIn(ewa))

        val announced = events.of<UserSessionsRevoked>().single()
        assertEquals(UserSessionsRevoked.RevocationReason.REMOTE_LOGOUT, announced.reason)
        assertEquals(ewa, announced.userId)
    }

    @Test
    fun `somebody else's session cannot be ended, and is not confirmed to exist`() {
        val theirs = signIn(marek)

        assertFailsWith<UnknownSessionException> { service.endSession(ewa, theirs) }
        assertEquals(1, service.sessionsOf(marek).size, "and it is still open")
    }

    @Test
    fun `a session that was already ended cannot be ended again`() {
        val laptop = signIn(ewa)
        service.endSession(ewa, laptop)

        assertFailsWith<UnknownSessionException> { service.endSession(ewa, laptop) }
    }

    /**
     * Signing somebody out of the tab they are pressing the button in, in the middle of
     * securing their account, is the worst possible moment to make them log in again.
     */
    @Test
    fun `signing out everywhere else keeps the session doing the asking`() {
        val here = signIn(ewa)
        signIn(ewa)
        signIn(ewa)

        assertEquals(2, service.endEverySessionExcept(ewa, here))
        assertEquals(listOf(here), service.sessionsOf(ewa).map { it.familyId })
        assertTrue(tokens.live().all { it.familyId == here })
    }

    @Test
    fun `signing out everywhere else touches nobody else's account`() {
        val mine = signIn(ewa)
        val theirs = signIn(marek)

        service.endEverySessionExcept(ewa, mine)

        assertEquals(listOf(theirs), service.sessionsOf(marek).map { it.familyId })
    }

    @Test
    fun `with nothing else open, signing out everywhere else announces nothing`() {
        val here = signIn(ewa)

        assertEquals(0, service.endEverySessionExcept(ewa, here))
        assertEquals(emptyList(), events.of<UserSessionsRevoked>())
    }

    /**
     * The one message a security-conscious product owes without being asked: somebody
     * whose password has been taken finds out from a warning about a device they do not
     * recognise, and from nothing else.
     */
    @Test
    fun `signing in on a device this account has not used before is announced`() {
        signIn(ewa, userAgent = "Mozilla/5.0 (Macintosh)")
        val phone = signIn(ewa, userAgent = "Mozilla/5.0 (iPhone)")

        val announced = events.of<SignedInFromNewDevice>().single()
        assertEquals(ewa, announced.userId)
        assertEquals(phone, announced.sessionId)
        assertEquals("Mozilla/5.0 (iPhone)", announced.userAgent)
    }

    /**
     * The first sign-in an account ever makes is the person who has just registered. A
     * message telling them they signed in is the kind of mail that teaches people to
     * ignore this one.
     */
    @Test
    fun `the first sign-in of all is not news`() {
        signIn(ewa)

        assertEquals(emptyList(), events.of<SignedInFromNewDevice>())
    }

    @Test
    fun `signing in again on a device already used says nothing`() {
        signIn(ewa, userAgent = "Mozilla/5.0 (Macintosh)")
        signIn(ewa, userAgent = "Mozilla/5.0 (Macintosh)")

        assertEquals(emptyList(), events.of<SignedInFromNewDevice>())
    }

    /**
     * With nothing to compare, every sign-in looks new — and a warning that fires every
     * time is one nobody reads by the second week.
     */
    @Test
    fun `a client that names itself nothing is not called a new device`() {
        signIn(ewa, userAgent = "Mozilla/5.0 (Macintosh)")
        signIn(ewa, userAgent = null)

        assertEquals(emptyList(), events.of<SignedInFromNewDevice>())
    }

    @Test
    fun `another account's devices are not this one's history`() {
        signIn(marek, userAgent = "Mozilla/5.0 (Macintosh)")
        signIn(ewa, userAgent = "Mozilla/5.0 (Macintosh)")

        assertEquals(emptyList(), events.of<SignedInFromNewDevice>(), "it is still their first sign-in")
    }

    /** A login: a family of refresh tokens, and the session row that names the device. */
    private fun signIn(
        user: UserId,
        userAgent: String? = "Mozilla/5.0",
        clientIp: String? = "203.0.113.7",
    ): UUID {
        val family = Ids.next()
        tokens.add(
            RefreshToken(
                id = Ids.next(),
                userId = user.value,
                tokenHash = Ids.next().toString(),
                familyId = family,
                expiresAt = clock.instant().plus(Duration.ofDays(30)),
                createdAt = clock.instant(),
            ),
        )
        service.openSession(user.value, family, ClientFingerprint(userAgent, clientIp))
        // A second apart, so "most recent first" is a real ordering rather than a tie.
        clock.advanceBy(Duration.ofSeconds(1))

        return family
    }
}
