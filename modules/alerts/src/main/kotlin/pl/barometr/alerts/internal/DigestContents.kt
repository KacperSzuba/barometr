package pl.barometr.alerts.internal

import java.time.Instant

/**
 * One window, composed the way somebody reads it: by matter, not by arrival.
 *
 * A draft that moved and then got published is one thing to a reader and two rows to
 * this system, so the grouping is what turns a list into something worth opening.
 *
 * Ordered by recency within and between groups. The specification asks for significance
 * and nothing computes one yet; recency is the honest stand-in, and saying so here is
 * better than a `sortedBy { importance }` over a column of zeroes.
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
                            notifications = about.sortedByDescending { it.createdAt },
                        )
                    }
                    .sortedByDescending { it.latest },
            )
    }
}
