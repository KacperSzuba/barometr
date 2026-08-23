package pl.barometr.alerts.internal

import java.time.Instant

/**
 * One window, composed the way somebody reads it: by matter, not by arrival.
 *
 * A draft that moved and then got published is one thing to a reader and two rows to
 * this system, so the grouping is what turns a list into something worth opening.
 *
 * **Ordered by significance between matters, by recency within one.** The two are
 * different questions. Which matter to read first is a judgement, and the digest is
 * worth opening only if it makes that judgement well; what happened inside one matter
 * is a story, and a story runs in time. A matter is as significant as the most
 * significant thing that happened in it — the draft that reached its third reading
 * does not become less important because a minor filing followed it.
 */
data class DigestContents(
    val digest: Digest,
    val matters: List<Matter>,
) {
    /** Everything in this window about one act or one draft. */
    data class Matter(
        val subjectKind: String,
        val subjectId: String,
        val title: String,
        val latest: Instant,
        /** The highest score anything in this matter was given. */
        val significance: Int,
        val notifications: List<Notification>,
    )

    companion object {
        fun of(digest: Digest, notifications: List<Notification>): DigestContents =
            DigestContents(
                digest,
                notifications.groupBy { it.subjectKind to it.subjectId }
                    .map { (subject, about) ->
                        val newest = about.maxBy { it.createdAt }
                        Matter(
                            subjectKind = subject.first,
                            subjectId = subject.second,
                            title = newest.title,
                            latest = newest.createdAt,
                            significance = about.maxOf { it.significance.score },
                            notifications = about.sortedByDescending { it.createdAt },
                        )
                    }
                    // Recency breaks the tie, which is most of a quiet week: nothing
                    // scored differently, and then the order somebody expects is the
                    // order it happened in.
                    .sortedWith(compareByDescending<Matter> { it.significance }.thenByDescending { it.latest }),
            )
    }
}
