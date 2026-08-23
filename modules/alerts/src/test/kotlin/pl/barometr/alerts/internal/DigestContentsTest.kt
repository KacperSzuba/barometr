package pl.barometr.alerts.internal

import org.junit.jupiter.api.Test
import pl.barometr.identity.api.UserId
import pl.barometr.profiles.api.InterestKind
import pl.barometr.profiles.api.MatchedInterest
import pl.barometr.profiles.api.ProfileId
import pl.barometr.shared.Ids
import java.time.Instant
import kotlin.test.assertEquals

/**
 * How a window reads.
 *
 * The composition used to be ordered by arrival and said so in a comment: the
 * specification asked for significance and nothing computed one. Something does now,
 * and what these pin is the judgement that replaced the stand-in.
 */
class DigestContentsTest {

    @Test
    fun `the most significant matter is read first, whatever arrived last`() {
        val contents = DigestContents.of(
            digest(),
            listOf(
                told("minor", at = "09:00", significance = 20),
                told("major", at = "08:00", significance = 80),
            ),
        )

        assertEquals(listOf("major", "minor"), contents.matters.map { it.title })
    }

    /**
     * Most of a quiet week: nothing scored differently, and then the order somebody
     * expects is the order it happened in.
     */
    @Test
    fun `recency breaks a tie`() {
        val contents = DigestContents.of(
            digest(),
            listOf(
                told("earlier", at = "08:00", significance = 40),
                told("later", at = "09:00", significance = 40),
            ),
        )

        assertEquals(listOf("later", "earlier"), contents.matters.map { it.title })
    }

    /**
     * A draft that reached its third reading does not become less important because a
     * minor filing followed it, so a matter is as significant as the most significant
     * thing that happened in it.
     */
    @Test
    fun `a matter is worth as much as the best thing in it`() {
        val subject = Ids.next().toString()
        val contents = DigestContents.of(
            digest(),
            listOf(
                told("third reading", at = "08:00", significance = 90, subject = subject),
                told("a filing", at = "09:00", significance = 10, subject = subject),
            ),
        )

        val matter = contents.matters.single()
        assertEquals(90, matter.significance)
        // The story inside one matter still runs in time, newest first.
        assertEquals(listOf("a filing", "third reading"), matter.notifications.map { it.title })
    }

    private fun digest() = Digest(Ids.next(), Instant.parse("2026-08-22T10:00:00Z"))

    private fun told(
        title: String,
        at: String,
        significance: Int,
        subject: String = Ids.next().toString(),
    ) = Notification(
        id = Ids.next(),
        owner = OWNER,
        profile = ProfileId(Ids.next()),
        profileVersion = 1,
        subjectKind = "draft",
        subjectId = subject,
        title = title,
        urgency = Urgency.NORMAL,
        significance = Significance(significance, emptyList()),
        matchedBy = MatchedInterest(InterestKind.KEYWORD, "prawo"),
        createdAt = Instant.parse("2026-08-22T$at:00Z"),
        readAt = null,
    )

    private companion object {
        val OWNER = UserId.next()
    }
}
