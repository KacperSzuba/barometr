package pl.barometr.profiles.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.PublishedAct
import pl.barometr.legislative.api.TrackedDraft
import pl.barometr.profiles.internal.jooq.tables.references.INTEREST_PROFILE
import pl.barometr.search.api.TitleMatch
import pl.barometr.search.api.TitleSearch
import pl.barometr.shared.Eli
import pl.barometr.shared.Ids
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

    private val dsl = PostgresTestDatabase.dsl()
    private val profiles = InterestProfiles(
        InterestProfileRepository(dsl, TestClock()),
        InterestNormalizer(),
    )

    private val catalog = FakeCatalog()
    private val titles = FakeTitleSearch()
    private val preview = ProfileMatchPreview(profiles, catalog, titles)

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
     * An industry and a place are not silent, they are dormant: nothing records what an
     * act is about until impact analysis does, and telling somebody their code matched
     * nothing would be an answer we have not earned.
     */
    @Test
    fun `an industry and a place are reported dormant rather than silent`() {
        val profile = profiles.create(
            ewa,
            "Budowlanka",
            listOf(Interest(InterestKind.PKD, "41.20.Z"), Interest(InterestKind.REGION, "14")),
        )

        val found = preview.preview(ewa, profile.id)

        assertEquals(listOf("41.20.Z", "14"), found.dormant.map { it.value }.sortedDescending())
        assertEquals(emptyList(), found.silent)
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
