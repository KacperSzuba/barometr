package pl.barometr.corpus.internal.diff

import org.springframework.stereotype.Component
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobPriority
import pl.barometr.platform.JobQueue
import pl.barometr.platform.JobType
import pl.barometr.platform.NewJob
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * The only way a comparison reaches the queue.
 *
 * The job type a handler registers against, the dedup key that decides whether the
 * work is already in flight, and the payload format are one fact and live here
 * together — the lesson of a payload once built by string interpolation, which
 * dead-lettered the moment an identifier contained a quote.
 *
 * Comparisons are queued rather than run where they are noticed. A three-hundred-page
 * pair is seconds of parsing and matching, and a listener that did it inline would hold
 * the event register's transaction open for all of them.
 */
@Component
class VersionDiffQueue(
    private val queue: JobQueue,
    private val json: ObjectMapper,
) {

    /**
     * Queues one comparison. Returns false when the same pair is already pending or
     * running — the database's decision, taken through the dedup key rather than by a
     * read-then-write race between the listener and the sweep.
     */
    fun queueComparison(pair: ComparablePair): Boolean = queue.enqueue(
        NewJob(
            type = TYPE,
            payload = json.writeValueAsString(
                WirePayload(
                    fromVersionId = pair.fromVersionId.value.toString(),
                    toVersionId = pair.toVersionId.value.toString(),
                ),
            ),
            // Behind live ingestion: a comparison is worth having by the time somebody
            // opens the card, not by the time the crawl finishes.
            priority = JobPriority.BACKGROUND,
            dedupKey = "corpus.diff:${pair.fromVersionId.value}:${pair.toVersionId.value}",
        ),
    )

    fun pairRequestedBy(job: ClaimedJob): RequestedComparison {
        val wire = json.readValue(job.payload, WirePayload::class.java)
        return RequestedComparison(
            fromVersionId = DocumentVersionId(UUID.fromString(wire.fromVersionId)),
            toVersionId = DocumentVersionId(UUID.fromString(wire.toVersionId)),
        )
    }

    /**
     * What actually sits in the queue's payload column: wire forms, converted to
     * domain types at this boundary and nowhere else.
     */
    internal data class WirePayload(val fromVersionId: String, val toVersionId: String)

    companion object {
        val TYPE = JobType("corpus.version-diff")
    }
}
