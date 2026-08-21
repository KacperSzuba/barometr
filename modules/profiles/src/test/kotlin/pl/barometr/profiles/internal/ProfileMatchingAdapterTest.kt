package pl.barometr.profiles.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.profiles.api.LegislativeItem
import pl.barometr.profiles.internal.jooq.tables.references.INTEREST_PROFILE
import pl.barometr.search.api.TextAnalysis
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The question the alerting stands on: one act arrives, who asked for it.
 *
 * Against real Postgres, because the keyword half of the answer is array containment
 * and the whole point of storing stems was to let the database answer it. The stemmer
 * is faked — cutting words short models the property that matters here, that two forms
 * of a word arrive as one token — and the real one is tested where it lives, against a
 * real node.
 */
class ProfileMatchingAdapterTest {

    private val dsl = PostgresTestDatabase.dsl()
    private val profiles = InterestProfiles(
        InterestProfileRepository(dsl, TestClock()),
        InterestNormalizer(),
    )

    private val analysis = FakeAnalysis()
    private val matching = ProfileMatchingAdapter(dsl, KeywordStemRepository(dsl), analysis)

    private val ewa = UserId.next()
    private val marek = UserId.next()

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(INTEREST_PROFILE).execute()
        analysis.forget()
    }

    @Test
    fun `an address catches the act published at it`() {
        val profile = profiles.create(ewa, "Budowlanka", listOf(Interest(InterestKind.ACT, "DU/2024/1222")))

        val interested = matching.profilesInterestedIn(act("DU/2024/1222", "Prawo budowlane")).single()

        assertEquals(profile.id, interested.profile)
        assertEquals(ewa, interested.owner)
        assertEquals(1, interested.version)
        assertEquals("act", interested.matchedBy.kind)
    }

    @Test
    fun `an address catches nothing else`() {
        profiles.create(ewa, "Budowlanka", listOf(Interest(InterestKind.ACT, "DU/2024/1222")))

        assertEquals(emptyList(), matching.profilesInterestedIn(act("DU/2024/17", "Prawo budowlane")))
    }

    @Test
    fun `a watched draft is caught by the identity the tracker gave it`() {
        val draftId = UUID.randomUUID().toString()
        profiles.create(ewa, "Drony", listOf(Interest(InterestKind.DRAFT, draftId)))

        val item = LegislativeItem(LegislativeKind.DRAFT, draftId, "Projekt ustawy o dronach")

        assertEquals(1, matching.profilesInterestedIn(item).size)
    }

    /**
     * A draft's identifier could in principle be typed as an act's; the kind is what
     * stops an act from being caught by a draft interest that happens to share a value.
     */
    @Test
    fun `a draft interest does not catch an act`() {
        val id = UUID.randomUUID().toString()
        profiles.create(ewa, "Drony", listOf(Interest(InterestKind.DRAFT, id)))

        assertEquals(emptyList(), matching.profilesInterestedIn(LegislativeItem("act", id, "Cokolwiek")))
    }

    @Test
    fun `a keyword catches a title carrying the same words in another form`() {
        profiles.create(ewa, "Budowlanka", listOf(Interest(InterestKind.KEYWORD, "prawo budowlane")))

        val interested = matching.profilesInterestedIn(act("DU/2024/1222", "Ustawa Prawo budowlanego")).single()

        assertEquals("keyword", interested.matchedBy.kind)
        assertEquals("prawo budowlane", interested.matchedBy.value)
    }

    /**
     * Every word, not any: a subscription to *prawo budowlane* that fired on the word
     * *prawo* would report most of the Journal of Laws.
     */
    @Test
    fun `a keyword needs all of its words in the title`() {
        profiles.create(ewa, "Budowlanka", listOf(Interest(InterestKind.KEYWORD, "prawo budowlane")))

        assertEquals(emptyList(), matching.profilesInterestedIn(act("DU/2024/17", "Ustawa Prawo o dronach")))
    }

    @Test
    fun `an exclusion silences a profile that would otherwise be told`() {
        profiles.create(
            ewa,
            "Budowlanka",
            listOf(
                Interest(InterestKind.KEYWORD, "prawo budowlane"),
                Interest(InterestKind.ACT, "DU/2024/1222", excluded = true),
            ),
        )

        assertEquals(emptyList(), matching.profilesInterestedIn(act("DU/2024/1222", "Prawo budowlane")))
    }

    @Test
    fun `an exclusion silences only the profile that made it`() {
        profiles.create(
            ewa,
            "Budowlanka",
            listOf(
                Interest(InterestKind.KEYWORD, "prawo budowlane"),
                Interest(InterestKind.ACT, "DU/2024/1222", excluded = true),
            ),
        )
        profiles.create(marek, "Budowlanka", listOf(Interest(InterestKind.KEYWORD, "prawo budowlane")))

        val interested = matching.profilesInterestedIn(act("DU/2024/1222", "Prawo budowlane")).single()

        assertEquals(marek, interested.owner)
    }

    /**
     * Matching is against what a profile says now. The old versions are kept to explain
     * what was already sent, not to keep sending it.
     */
    @Test
    fun `an interest removed by an edit stops catching anything`() {
        val profile = profiles.create(ewa, "Budowlanka", listOf(Interest(InterestKind.ACT, "DU/2024/1222")))

        profiles.revise(ewa, profile.id, listOf(Interest(InterestKind.ACT, "DU/2024/17")))

        assertEquals(emptyList(), matching.profilesInterestedIn(act("DU/2024/1222", "Prawo budowlane")))
        assertEquals(1, matching.profilesInterestedIn(act("DU/2024/17", "Prawa konsumenta")).size)
    }

    /**
     * The stems are derived and kept, so a keyword costs one analyse the first time it
     * is seen and none afterwards — which is what makes this affordable per document.
     */
    @Test
    fun `a keyword is stemmed once and remembered`() {
        profiles.create(ewa, "Budowlanka", listOf(Interest(InterestKind.KEYWORD, "prawo budowlane")))

        matching.profilesInterestedIn(act("DU/2024/1222", "Prawo budowlane"))
        val afterFirst = analysis.calls
        matching.profilesInterestedIn(act("DU/2024/17", "Prawo budowlane"))

        assertEquals(2, afterFirst, "one for the title, one for the new keyword")
        assertEquals(3, analysis.calls, "the second run stems only the title")
    }

    @Test
    fun `a title of nothing but noise catches no keyword`() {
        profiles.create(ewa, "Budowlanka", listOf(Interest(InterestKind.KEYWORD, "prawo budowlane")))

        assertTrue(matching.profilesInterestedIn(act("DU/2024/99", "o w na")).isEmpty())
    }

    private fun act(eli: String, title: String) = LegislativeItem(LegislativeKind.ACT, eli, title, eli)

    /**
     * Stemming, modelled rather than reproduced: words are cut to their first five
     * letters and the shortest are dropped, which makes *budowlane* and *budowlanego*
     * one token the way the real analyser does, without pretending to be Polish.
     */
    private class FakeAnalysis : TextAnalysis {
        var calls = 0
            private set

        fun forget() {
            calls = 0
        }

        override fun stemsOf(text: String): List<String> {
            calls++
            return text.lowercase()
                .split(Regex("\\W+"))
                .filter { it.length > NOISE }
                .map { it.take(STEM) }
        }

        private companion object {
            const val NOISE = 2
            const val STEM = 5
        }
    }
}
