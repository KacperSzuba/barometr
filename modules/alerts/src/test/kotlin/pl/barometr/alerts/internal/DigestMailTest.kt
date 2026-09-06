package pl.barometr.alerts.internal

import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.MatchedInterest
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Ids
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /**
     * The one line in this mail that asks the reader to do something, and the reason
     * the digest is worth opening the day it arrives rather than at the weekend.
     */
    @Test
    fun `a consultation deadline is stated in full, in both renderings`() {
        val message = compose(
            listOf(matter("Projekt ustawy o kredycie konsumenckim", "kredyt", closesOn = LocalDate.of(2026, 4, 30))),
        )

        assertTrue(message.text.contains("Termin zgłaszania uwag: 30.04.2026"), message.text)
        assertTrue(message.html.contains("Termin zgłaszania uwag: 30.04.2026"), message.html)
    }

    /** Most matters are news about something that already happened, and carry no date. */
    @Test
    fun `a matter with no deadline says nothing about one`() {
        val message = compose(listOf(matter("Ustawa o dostępie do informacji publicznej", "informacja")))

        assertFalse(message.text.contains("Termin"), message.text)
    }

    /**
     * The second and last thing an inbox shows before somebody decides. Without it the
     * preview is whatever text comes first in the message, which in a styled mail is
     * usually nothing at all.
     */
    @Test
    fun `the inbox preview names the matter the digest put first`() {
        val message = compose(
            listOf(
                matter("Prawo budowlane", "prawo", closesOn = LocalDate.of(2026, 4, 30)),
                matter("Ustawa o cenach energii", "energia"),
            ),
        )

        assertTrue(
            message.html.contains("Prawo budowlane · Termin zgłaszania uwag: 30.04.2026"),
            message.html,
        )
    }

    /**
     * The digest orders by significance and used to show nothing of it, so the first
     * entry looked arbitrary to anybody whose own reading of the week differed.
     */
    @Test
    fun `why a matter is near the top is said, in both renderings`() {
        val message = compose(
            listOf(
                matter(
                    "Ustawa o odnawialnych źródłach energii",
                    "energia",
                    significance = Significance(
                        80,
                        listOf(SignificanceReason.NEARING_ENACTMENT, SignificanceReason.DEADLINE_IMMINENT),
                    ),
                ),
            ),
        )

        assertTrue(message.text.contains("Blisko uchwalenia · Termin w tym tygodniu"), message.text)
        assertTrue(message.html.contains("Blisko uchwalenia · Termin w tym tygodniu"), message.html)
    }

    /**
     * Four things happening to one bill in a week is a different week from one, and the
     * grouping into matters is what would otherwise hide the difference.
     */
    @Test
    fun `a matter says how much happened in it`() {
        val once = compose(listOf(matter("Prawo budowlane", "prawo")))
        val often = compose(listOf(matter("Prawo budowlane", "prawo", events = 3)))

        assertTrue(once.text.contains("Ostatnia zmiana: 22.08.2026"), once.text)
        assertTrue(often.text.contains("Zmian w tej sprawie: 3, ostatnia: 22.08.2026"), often.text)
    }

    /**
     * Written the way e-mail is written rather than the way a page is: Outlook renders
     * a fraction of CSS and Gmail discards anything in `<head>`, so a stylesheet would
     * arrive as a stack of unstyled text — for a reason invisible from a browser.
     */
    @Test
    fun `the html is a document an e-mail client can render`() {
        val message = compose(listOf(matter("Prawo budowlane", "prawo")))

        assertTrue(message.html.startsWith("<!doctype html>"), message.html.take(40))
        assertTrue(message.html.contains("""<meta name="color-scheme" content="light dark">"""))
        assertTrue(message.html.contains("""role="presentation""""), "layout tables are not read out")
        assertFalse(message.html.contains("<style"), "a client that drops a stylesheet still renders this")
        assertFalse(message.html.contains("<img"), "nothing here depends on images being loaded")
    }

    /**
     * The plain part is not a fallback: a message with only HTML scores worse with
     * every spam filter there is, and these alerts are the kind that must not land in a
     * junk folder.
     */
    @Test
    fun `both bodies carry every matter, in the same order`() {
        val titles = listOf("Prawo budowlane", "Ustawa o cenach energii", "Prawo bankowe")
        val message = compose(titles.map { matter(it, "prawo") })

        assertEquals(titles, titles.sortedBy { message.text.indexOf(it) })
        assertEquals(titles, titles.sortedBy { message.html.indexOf(it) })
        titles.forEach { assertTrue(message.html.contains(it), it) }
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
        closesOn: LocalDate? = null,
        significance: Significance = Significance(0, emptyList()),
        events: Int = 1,
    ) = DigestContents.Matter(
        subjectKind = "act",
        subjectId = UUID.randomUUID().toString(),
        title = title,
        latest = Instant.parse("2026-08-22T08:00:00Z"),
        significance = significance.score,
        notifications = List(events) {
            Notification(
                id = Ids.next(),
                owner = UserId.next(),
                profile = ProfileId(Ids.next()),
                profileVersion = 1,
                subjectKind = "act",
                subjectId = UUID.randomUUID().toString(),
                title = title,
                urgency = Urgency.NORMAL,
                significance = significance,
                matchedBy = MatchedInterest(kind, matched),
                closesOn = closesOn,
                createdAt = Instant.parse("2026-08-22T08:00:00Z"),
                readAt = null,
            )
        },
    )

    private companion object {
        const val UNSUBSCRIBE = "https://barometr.example/api/v1/alerts/unsubscribe/abc"
    }
}
