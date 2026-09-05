package pl.barometr.profiles.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.legislative.api.LegislativeSignals
import pl.barometr.legislative.api.PublishedAct
import pl.barometr.legislative.api.TrackedDraft
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.internal.jooq.tables.references.INTEREST_PROFILE
import pl.barometr.search.api.TitleMatch
import pl.barometr.search.api.TitleSearch
import pl.barometr.shared.Eli
import pl.barometr.shared.Ids
import pl.barometr.taxonomy.api.ClassifiedSubject
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a profile catches, and — as much to the point — what it honestly cannot.
 *
 * The catalog and the index are hand-written fakes holding a handful of acts: the
 * question here is which interests reach which lookup and what an exclusion removes,
 * and a real index would answer a different question slowly.
 */
class ProfileMatchPreviewTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val profiles = InterestProfiles(
        InterestProfileRepository(dsl, TestClock()),
        InterestNormalizer(),
    )

    private val catalog = FakeCatalog()
    private val titles = FakeTitleSearch()
    private val industries = FakeIndustries()
    private val preview = ProfileMatchPreview(profiles, catalog, titles, industries)

    private val ewa = UserId.next()

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(INTEREST_PROFILE).execute()
    }

    @Test
    fun `an address is answered by the catalog, not by the index`() {
        catalog.holds(BUILDING_ACT)
        val profile = profiles.create(ewa, "Budowlanka", listOf(act("DU/2024/1222")))

        val found = preview.preview(ewa, profile.id)

        assertEquals(listOf("Prawo budowlane"), found.matches.map { it.title })
        assertEquals(0, titles.calls)
    }

    @Test
    fun `a phrase is answered by the index`() {
        titles.finds("budowlane", TitleMatch("act", "a-1", "Prawo budowlane", "DU/2024/1222"))
        val profile = profiles.create(ewa, "Budowlanka", listOf(keyword("budowlane")))

        assertEquals(listOf("Prawo budowlane"), preview.preview(ewa, profile.id).matches.map { it.title })
    }

    /**
     * A correct code that matches nothing must not look like a typo, so an interest
     * that found nothing is reported as itself rather than left out.
     */
    @Test
    fun `an interest that finds nothing is reported as silent`() {
        val profile = profiles.create(ewa, "Budowlanka", listOf(act("DU/2024/9999")))

        val found = preview.preview(ewa, profile.id)

        assertEquals(emptyList(), found.matches)
        assertEquals(listOf("DU/2024/9999"), found.silent.map { it.value })
    }

    /**
     * A place is not silent, it is dormant: nothing records where a law applies, and
     * telling somebody their region matched nothing would be an answer we have not
     * earned. An industry left this category when taxonomy began recording what a law
     * is about.
     */
    @Test
    fun `a place is reported dormant rather than silent`() {
        val profile = profiles.create(ewa, "Mazowieckie", listOf(Interest(InterestKind.REGION, "14")))

        val found = preview.preview(ewa, profile.id)

        assertEquals(listOf("14"), found.dormant.map { it.value })
        assertEquals(emptyList(), found.silent)
    }

    @Test
    fun `an industry catches what has been classified beneath it`() {
        catalog.holds(BUILDING_ACT)
        industries.classifies(ClassifiedSubject(LegislativeKind.ACT, BUILDING_ACT.id.value), "41.20.Z")

        val profile = profiles.create(ewa, "Budowlanka", listOf(Interest(InterestKind.PKD, "41")))

        val found = preview.preview(ewa, profile.id)

        assertEquals(listOf("Prawo budowlane"), found.matches.map { it.title })
        assertEquals(emptyList(), found.silent)
        assertEquals(emptyList(), found.dormant)
    }

    /**
     * An industry nothing carries is silent rather than dormant, and the difference is
     * the whole point of the distinction: "nobody has tagged anything in your industry
     * yet" is a true answer this system can now give, where "we do not record that at
     * all" was the true answer before.
     */
    @Test
    fun `an industry nothing has been classified under is silent`() {
        val profile = profiles.create(ewa, "Rybactwo", listOf(Interest(InterestKind.PKD, "03.11.Z")))

        val found = preview.preview(ewa, profile.id)

        assertEquals(emptyList(), found.matches)
        assertEquals(listOf("03.11.Z"), found.silent.map { it.value })
    }

    @Test
    fun `an excluded industry removes what it covers`() {
        catalog.holds(BUILDING_ACT)
        industries.classifies(ClassifiedSubject(LegislativeKind.ACT, BUILDING_ACT.id.value), "41.20.Z")
        titles.finds("budowlane", TitleMatch("act", BUILDING_ACT.id.value.toString(), "Prawo budowlane", "DU/2024/1222"))

        val profile = profiles.create(
            ewa,
            "Budowlanka",
            listOf(keyword("budowlane"), Interest(InterestKind.PKD, "41", excluded = true)),
        )

        val found = preview.preview(ewa, profile.id)

        assertEquals(emptyList(), found.matches)
    }

    @Test
    fun `an exclusion removes what an inclusion found`() {
        titles.finds(
            "budowlane",
            TitleMatch("act", "a-1", "Prawo budowlane", "DU/2024/1222"),
            TitleMatch("act", "a-2", "Prawo budowlane zmiana", "DU/2025/7"),
        )
        val profile = profiles.create(
            ewa,
            "Budowlanka",
            listOf(keyword("budowlane"), Interest(InterestKind.ACT, "DU/2025/7", excluded = true)),
        )

        val found = preview.preview(ewa, profile.id)

        assertEquals(listOf("DU/2024/1222"), found.matches.map { it.eli })
        // The interest that survived one exclusion is not silent: it still catches.
        assertEquals(emptyList(), found.silent)
    }

    /**
     * The excluded phrase goes back to the index, so it excludes everything the same
     * word would have found — `drony` reaches `dronach`, which a substring comparison
     * against the title would not.
     */
    @Test
    fun `an excluded word removes what that word finds, stemming included`() {
        val drones = TitleMatch("act", "a-2", "Prawo o dronach", "DU/2025/9")
        titles.finds("prawo", TitleMatch("act", "a-1", "Prawo budowlane", "DU/2024/1222"), drones)
        titles.finds("drony", drones)
        val profile = profiles.create(
            ewa,
            "Wszystko",
            listOf(keyword("prawo"), Interest(InterestKind.KEYWORD, "drony", excluded = true)),
        )

        val found = preview.preview(ewa, profile.id)

        assertEquals(listOf("Prawo budowlane"), found.matches.map { it.title })
    }

    @Test
    fun `two interests finding the same act report it once`() {
        catalog.holds(BUILDING_ACT)
        titles.finds("budowlane", TitleMatch("act", BUILDING_ACT.id.value.toString(), "Prawo budowlane", "DU/2024/1222"))
        val profile = profiles.create(
            ewa,
            "Budowlanka",
            listOf(act("DU/2024/1222"), keyword("budowlane")),
        )

        assertEquals(1, preview.preview(ewa, profile.id).matches.size)
    }

    @Test
    fun `a draft is resolved by the identity the tracker gave it`() {
        val draft = TrackedDraft(
            id = DraftId(Ids.next()),
            title = "Projekt ustawy o dronach",
            initiator = "government",
            term = 10,
            startedOn = LocalDate.of(2025, 3, 1),
            closedOn = null,
            outcome = null,
            currentStage = "committee_work",
            identifiers = listOf("UD383"),
        )
        catalog.holds(draft)
        val profile = profiles.create(
            ewa,
            "Drony",
            listOf(Interest(InterestKind.DRAFT, draft.id.value.toString())),
        )

        val found = preview.preview(ewa, profile.id).matches.single()

        assertEquals("draft", found.kind)
        assertTrue(found.eli == null)
    }

    private fun act(eli: String) = Interest(InterestKind.ACT, eli)

    private fun keyword(word: String) = Interest(InterestKind.KEYWORD, word)

    private class FakeCatalog : LegislativeCatalog {
        private val acts = mutableListOf<PublishedAct>()
        private val drafts = mutableListOf<TrackedDraft>()

        fun holds(act: PublishedAct) = acts.add(act)

        fun holds(draft: TrackedDraft) = drafts.add(draft)

        override fun actById(id: ActId) = acts.firstOrNull { it.id == id }

        override fun actByEli(eli: Eli) = acts.firstOrNull { it.eli == eli }

        override fun draftById(id: DraftId) = drafts.firstOrNull { it.id == id }

        /** Nothing here ranks anything; the signals are somebody else's question. */
        override fun signalsForDraft(id: DraftId): LegislativeSignals? = null

        override fun actsAfter(after: ActId?, limit: Int) = acts

        override fun draftsAfter(after: DraftId?, limit: Int) = drafts
    }

    private class FakeTitleSearch : TitleSearch {
        private val byPhrase = mutableMapOf<String, List<TitleMatch>>()
        var calls = 0
            private set

        fun finds(phrase: String, vararg matches: TitleMatch) {
            byPhrase[phrase] = matches.toList()
        }

        override fun titlesMatching(phrase: String, limit: Int): List<TitleMatch> {
            calls++
            return byPhrase[phrase].orEmpty().take(limit)
        }
    }

    private companion object {
        val BUILDING_ACT = PublishedAct(
            id = ActId(Ids.next()),
            eli = Eli("DU/2024/1222"),
            title = "Prawo budowlane",
            type = "Ustawa",
            publisher = "DU",
            announcedOn = LocalDate.of(2024, 8, 1),
            inForceFrom = LocalDate.of(2024, 9, 1),
        )
    }
}
