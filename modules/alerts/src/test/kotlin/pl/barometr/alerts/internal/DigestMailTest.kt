package pl.barometr.alerts.internal

import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.MatchedInterest
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Ids
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the message says.
 *
 * Plain and Polish, and the two things it must get right are counting in Polish — where
 * two, three and four are their own case — and saying why each line is there. A digest
 * whose entries do not say what caught them cannot be acted on: the reader cannot tell
 * a keyword that is too broad from an act they genuinely watch, so they turn all of it
 * off rather than the one line.
 */
class DigestMailTest {

    private val mail = DigestMail()

    @Test
    fun `the subject counts the way Polish counts`() {
        assertEquals("Barometr: 1 sprawa", subjectFor(1))
        assertEquals("Barometr: 2 sprawy", subjectFor(2))
        assertEquals("Barometr: 4 sprawy", subjectFor(4))
        assertEquals("Barometr: 5 spraw", subjectFor(5))
        // The rule is not "two to four": 22 takes the same case as 2, and 12 does not.
        assertEquals("Barometr: 12 spraw", subjectFor(12))
        assertEquals("Barometr: 13 spraw", subjectFor(13))
        assertEquals("Barometr: 22 sprawy", subjectFor(22))
        assertEquals("Barometr: 25 spraw", subjectFor(25))
    }

    @Test
    fun `every line says what caught it`() {
        val message = compose(listOf(matter("Prawo budowlane", "41.20.Z", InterestKind.PKD)))

        assertTrue(message.text.contains("Prawo budowlane"))
        assertTrue(message.text.contains("Pasuje do: 41.20.Z"), message.text)
        assertTrue(message.html.contains("41.20.Z"))
    }

    @Test
    fun `the way out is in both bodies`() {
        val message = compose(listOf(matter("Prawo budowlane", "prawo", InterestKind.KEYWORD)))

        assertTrue(message.text.contains(UNSUBSCRIBE))
        assertTrue(message.html.contains(UNSUBSCRIBE))
        assertEquals(UNSUBSCRIBE, message.unsubscribeUrl)
    }

    /**
     * A title is a register's text, not ours. An act called `<b>` is not a plausible
     * attack, but the day a title carries an ampersand the mail should still render.
     */
    @Test
    fun `a title is escaped in the html and left alone in the text`() {
        val message = compose(listOf(matter("Ustawa o zmianie <ustawy> & innych", "prawo")))

        assertTrue(message.html.contains("&lt;ustawy&gt; &amp; innych"), message.html)
        assertTrue(message.text.contains("<ustawy> & innych"))
    }

    private fun subjectFor(matters: Int) =
        compose(List(matters) { matter("Ustawa numer $it", "prawo") }).subject

    private fun compose(matters: List<DigestContents.Matter>) =
        DigestMail().compose(
            DigestContents(Digest(Ids.next(), Instant.parse("2026-08-22T09:00:00Z")), matters),
            "ewa@example.com",
            UNSUBSCRIBE,
        )

    private fun matter(
        title: String,
        matched: String,
        kind: InterestKind = InterestKind.KEYWORD,
    ) = DigestContents.Matter(
        subjectKind = "act",
        subjectId = UUID.randomUUID().toString(),
        title = title,
        latest = Instant.parse("2026-08-22T08:00:00Z"),
        significance = 0,
        notifications = listOf(
            Notification(
                id = Ids.next(),
                owner = UserId.next(),
                profile = ProfileId(Ids.next()),
                profileVersion = 1,
                subjectKind = "act",
                subjectId = UUID.randomUUID().toString(),
                title = title,
                urgency = Urgency.NORMAL,
                significance = Significance(0, emptyList()),
                matchedBy = MatchedInterest(kind, matched),
                createdAt = Instant.parse("2026-08-22T08:00:00Z"),
                readAt = null,
            ),
        ),
    )

    private companion object {
        const val UNSUBSCRIBE = "https://barometr.example/api/v1/alerts/unsubscribe/abc"
    }
}
