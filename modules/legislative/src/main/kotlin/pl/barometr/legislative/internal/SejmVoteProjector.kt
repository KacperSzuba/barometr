package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore

/**
 * Turns an archived voting into a vote, and into the prints it decided.
 *
 * This is where "how did they vote on it" becomes answerable in SQL. The archive has
 * held every voting of every sitting since the connector learned to read a sitting;
 * until this ran, reaching one meant reading JSON out of object storage by hand.
 *
 * **A vote is recorded whether or not its bill is known.** The prints are stored as
 * addresses and resolved to drafts when somebody asks, so a sitting read before the
 * processes it voted on — which is the ordinary case, since the two are separate walks
 * — records votes that acquire their draft the moment the draft exists. Nothing has to
 * be re-derived, and nothing is lost for having arrived first.
 */
@Service
class SejmVoteProjector(
    private val blobs: BlobStore,
    private val reader: SejmVoteReader,
    private val votes: VoteRepository,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun projectVote(recorded: DocumentVersionRecorded) {
        if (recorded.kind != VOTING) return

        val payload = blobs.read(BlobBucket.RAW, recorded.contentHash)?.use { it.readBytes() }
        if (payload == null) {
            log.warn("No archived bytes for voting {} at {}", recorded.externalId, recorded.contentHash)
            return
        }

        val vote = reader.read(payload)
        if (vote == null) {
            meters.counter("legislative.vote.skipped", "reason", "unreadable").increment()
            log.debug("Voting {} is not readable as a vote", recorded.externalId)
            return
        }

        val voteId = votes.recordVote(vote, recorded.versionId)
        votes.citePrints(voteId, vote.citedPrints)

        meters.counter("legislative.vote.recorded").increment()
        if (vote.citedPrints.isEmpty()) {
            // A vote on a candidate, a motion or the order of the day cites no print and
            // never will, so this is not an error — it is the share of the voting record
            // that no bill will ever reach, and worth being able to state.
            meters.counter("legislative.vote.uncited").increment()
        }
    }

    private companion object {
        val VOTING = DocumentKind("voting")
    }
}
