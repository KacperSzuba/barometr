package pl.barometr.corpus.internal.diff

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobHandler
import pl.barometr.platform.JobType

/**
 * Runs one queued comparison.
 *
 * Everything the payload could get wrong is handled by [VersionDiffQueue], which hands
 * over two identities; what is left here is the decision that belongs to execution
 * time — whether both versions still have text the archive can read.
 */
@Component
class VersionDiffJobHandler(
    private val diffs: VersionDiffRepository,
    private val comparison: VersionComparison,
    private val queue: VersionDiffQueue,
) : JobHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    override val type: JobType = VersionDiffQueue.TYPE

    override fun handle(job: ClaimedJob) {
        val requested = queue.pairRequestedBy(job)
        val pair = diffs.pairOf(requested.fromVersionId, requested.toVersionId)

        if (pair == null) {
            // A version deleted, or one whose text is gone, between queueing and
            // claiming. Not a failure: there is nothing to compare, and retrying would
            // not make one appear.
            log.info("No comparable pair {} → {}; dropping", requested.fromVersionId, requested.toVersionId)
            return
        }

        // Any exception propagates: the queue applies backoff and retries.
        comparison.compareVersions(pair)
    }
}
