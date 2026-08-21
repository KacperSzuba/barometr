package pl.barometr.profiles.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.internal.jooq.tables.references.INTEREST_PROFILE
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A profile, its versions, and who may see it — against the schema the application
 * migrates, because the uniqueness and the cascade being tested are the database's
 * work rather than this code's.
 */
class InterestProfilesTest {

    private val dsl = PostgresTestDatabase.dsl()
    private val clock = TestClock()

    private val profiles = InterestProfiles(InterestProfileRepository(dsl, clock), InterestNormalizer())

    private val ewa = UserId.next()
    private val marek = UserId.next()

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(INTEREST_PROFILE).execute()
    }

    @Test
    fun `a new profile starts at version one and holds what was chosen`() {
        val profile = profiles.create(ewa, "Budowlanka", listOf(pkd("41.20.Z"), region("14")))

        assertEquals(1, profile.version)
        assertEquals(listOf("41.20.Z"), profile.of(InterestKind.PKD).map { it.value })
        assertEquals(profile, profiles.read(ewa, profile.id))
    }

    /**
     * The reason versions exist: an alert cites the version it matched against, and
     * has to still be explicable after the profile moved on twice.
     */
    @Test
    fun `editing writes a new version and leaves the old one readable`() {
        val first = profiles.create(ewa, "Budowlanka", listOf(pkd("41.20.Z")))

        val second = profiles.revise(ewa, first.id, listOf(pkd("62.01.Z")))

        assertEquals(2, second.version)
        assertEquals(listOf("62.01.Z"), profiles.read(ewa, first.id).interests.map { it.value })
        assertEquals(
            listOf("41.20.Z"),
            profiles.readVersion(ewa, first.id, 1).interests.map { it.value },
        )
        assertEquals(listOf(2, 1), profiles.history(ewa, first.id).map { it.version })
    }

    /**
     * An edit states the whole profile, so an interest is removed by sending the rest.
     * The version it was removed from still has it.
     */
    @Test
    fun `an interest left out of an edit is gone from the live version only`() {
        val profile = profiles.create(ewa, "Budowlanka", listOf(pkd("41.20.Z"), pkd("62.01.Z")))

        profiles.revise(ewa, profile.id, listOf(pkd("41.20.Z")))

        assertEquals(1, profiles.read(ewa, profile.id).interests.size)
        assertEquals(2, profiles.readVersion(ewa, profile.id, 1).interests.size)
    }

    @Test
    fun `the same interest typed twice is one interest`() {
        val profile = profiles.create(ewa, "Budowlanka", listOf(pkd(" 41.20.z"), pkd("41.20.Z")))

        assertEquals(listOf("41.20.Z"), profile.interests.map { it.value })
        assertEquals(1, profiles.read(ewa, profile.id).interests.size)
    }

    @Test
    fun `an industry inside one already chosen is not kept twice`() {
        val profile = profiles.create(ewa, "IT", listOf(pkd("62"), pkd("62.01.Z")))

        assertEquals(listOf("62"), profile.interests.map { it.value })
    }

    /**
     * "That industry, except this corner of it" — collapsing the pair would say the
     * opposite of what was asked.
     */
    @Test
    fun `an exclusion inside an industry that was chosen survives`() {
        val profile = profiles.create(
            ewa,
            "IT",
            listOf(pkd("62"), Interest(InterestKind.PKD, "62.01.Z", excluded = true)),
        )

        assertEquals(2, profile.interests.size)
    }

    @Test
    fun `an exclusion is stored as a choice, not as an absence`() {
        val profile = profiles.create(
            ewa,
            "Budowlanka",
            listOf(pkd("41.20.Z"), Interest(InterestKind.ACT, "DU/2024/1222", excluded = true)),
        )

        val excluded = profiles.read(ewa, profile.id).of(InterestKind.ACT).single()
        assertTrue(excluded.excluded)
    }

    @Test
    fun `one account cannot keep two profiles under one name`() {
        profiles.create(ewa, "Budowlanka", emptyList())

        assertFailsWith<DuplicateProfileNameException> {
            profiles.create(ewa, "Budowlanka", emptyList())
        }
    }

    @Test
    fun `two accounts may name their profiles the same`() {
        profiles.create(ewa, "Budowlanka", emptyList())
        profiles.create(marek, "Budowlanka", emptyList())

        assertEquals(1, profiles.ownedBy(ewa).size)
        assertEquals(1, profiles.ownedBy(marek).size)
    }

    /**
     * Not yours reads as not there. A `403` would confirm the profile exists, and a
     * profile's name is the sort of thing worth guessing.
     */
    @Test
    fun `somebody else's profile cannot be read, edited or deleted`() {
        val profile = profiles.create(ewa, "Budowlanka", emptyList())

        assertFailsWith<UnknownProfileException> { profiles.read(marek, profile.id) }
        assertFailsWith<UnknownProfileException> { profiles.revise(marek, profile.id, emptyList()) }
        assertFailsWith<UnknownProfileException> { profiles.rename(marek, profile.id, "Moje") }
        assertFailsWith<UnknownProfileException> { profiles.delete(marek, profile.id) }
    }

    @Test
    fun `renaming changes nothing the profile matches, so it writes no version`() {
        val profile = profiles.create(ewa, "Budowlanka", listOf(pkd("41.20.Z")))

        val renamed = profiles.rename(ewa, profile.id, "Budownictwo")

        assertEquals("Budownictwo", renamed.name)
        assertEquals(1, profiles.read(ewa, profile.id).version)
        assertEquals(listOf(1), profiles.history(ewa, profile.id).map { it.version })
    }

    @Test
    fun `renaming onto a name this account already uses is refused`() {
        profiles.create(ewa, "Budowlanka", emptyList())
        val other = profiles.create(ewa, "Podatki", emptyList())

        assertFailsWith<DuplicateProfileNameException> {
            profiles.rename(ewa, other.id, "Budowlanka")
        }
        assertEquals("Podatki", profiles.read(ewa, other.id).name)
    }

    @Test
    fun `deleting takes the versions with it`() {
        val profile = profiles.create(ewa, "Budowlanka", listOf(pkd("41.20.Z")))
        profiles.revise(ewa, profile.id, listOf(pkd("62.01.Z")))

        profiles.delete(ewa, profile.id)

        assertFailsWith<UnknownProfileException> { profiles.read(ewa, profile.id) }
        assertEquals(0, dsl.fetchCount(INTEREST_PROFILE))
    }

    @Test
    fun `a version that was never written is not found`() {
        val profile = profiles.create(ewa, "Budowlanka", emptyList())

        assertFailsWith<UnknownProfileException> { profiles.readVersion(ewa, profile.id, 7) }
    }

    @Test
    fun `a profile that chose nothing is still a profile`() {
        val profile = profiles.create(ewa, "Nic jeszcze", emptyList())

        assertEquals(emptyList(), profiles.read(ewa, profile.id).interests)
        assertNull(profiles.ownedBy(ewa).singleOrNull()?.interests?.firstOrNull())
    }

    private fun pkd(code: String) = Interest(InterestKind.PKD, code)

    private fun region(code: String) = Interest(InterestKind.REGION, code)
}
