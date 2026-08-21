package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.legislative.api.DraftId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import java.time.Clock

/**
 * Turns an archived legislative process into a draft and the path it has taken.
 *
 * This is where "where is this bill, and how did it get there" becomes answerable in
 * SQL. The register restates a process's whole history every time anything moves, so
 * this reads the history whole and records only what is not already held — which makes
 * a re-read free and a correction visible beside what it corrects.
 *
 * A process is not always a draft. Motions, lists of candidates and government
 * information all travel the same register and are all archived, because that is what
 * an archive is; only bills and resolutions become drafts, and the count of the rest
 * is a metric rather than a silence.
 */
@Service
class LegislativePathProjector(
    private val blobs: BlobStore,
    private val reader: SejmProcessReader,
    private val drafts: DraftRepository,
    private val identifiers: DraftIdentifierRepository,
    private val transitions: StageTransitionRepository,
    private val acts: ActIdentifierRepository,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun projectDraftPath(recorded: DocumentVersionRecorded) {
        if (recorded.kind != PROCESS) return

        val payload = blobs.read(BlobBucket.RAW, recorded.contentHash)?.use { it.readBytes() }
        if (payload == null) {
            log.warn("No archived bytes for process {} at {}", recorded.externalId, recorded.contentHash)
            return
        }

        val process = reader.read(payload)
        if (process == null) {
            skip("unreadable", recorded.externalId.value)
            return
        }
        if (!process.isDraft) {
            skip("not-a-draft", recorded.externalId.value)
            return
        }

        record(process, recorded)
    }

    private fun record(process: SejmProcessRecord, recorded: DocumentVersionRecorded) {
        val address = SejmPrintAddress.of(process.term, process.printNumber)
        val draft = draftOf(process)
        val draftId = identifiers.draftFor(DraftIdentifierScheme.SEJM_PRINT, address)
            ?: introduce(process, draft, address)

        drafts.restateDraft(draftId, draft)

        // The Council of Ministers' number for the same draft in RPL. Recorded now,
        // long before RPL is readable, because it is the only place the two registers
        // name each other and it costs nothing to keep.
        process.rclNumber?.let {
            identifiers.pointAtDraft(DraftIdentifierScheme.COUNCIL_OF_MINISTERS, it, draftId, MatchMethod.EXACT, confidence = 1.0)
        }

        // Only once the act exists here. A process states its ELI at publication, which
        // is usually before the act itself has been read, so the link is made by
        // whichever of the two arrives second.
        process.eli
            ?.let { acts.actFor(IdentifierScheme.ELI, it.value) }
            ?.let { drafts.linkToAct(draftId, it) }

        val facts = StageTimeline.of(process.stages)
        val recordedFacts = transitions.recordFacts(draftId, facts, recorded.versionId, clock.instant())
        countUnmappedStages(facts)

        if (recordedFacts > 0) {
            log.debug("Recorded {} new stages of draft {}", recordedFacts, address)
        }
    }

    /**
     * A draft nobody has seen before.
     *
     * The claim on the print number is what makes this safe under redelivery: two
     * deliveries both find nothing and both create a draft, and the second one's
     * transaction fails on the claim and is redelivered against the first one's row.
     */
    private fun draftOf(process: SejmProcessRecord) = DraftFromRegister(
        title = process.title,
        initiator = process.initiator,
        term = process.term,
        startedOn = process.startedOn,
        closedOn = process.closedOn,
        outcome = process.outcome,
    )

    private fun introduce(process: SejmProcessRecord, draft: DraftFromRegister, address: String): DraftId {
        val draftId = drafts.insertDraft(draft)
        identifiers.claimForDraft(DraftIdentifierScheme.SEJM_PRINT, address, draftId)

        if (process.initiator == DraftInitiator.UNKNOWN) {
            meters.counter("legislative.draft.initiator.unknown").increment()
            log.warn("No initiator recognised in '{}'", process.title.take(60))
        }

        return draftId
    }

    /**
     * Stages the register described in words this model has no name for.
     *
     * Counted by the source's own label, which is a closed vocabulary of about twenty
     * — small enough to tag, and exactly what a decision about extending the model
     * would need to see.
     */
    private fun countUnmappedStages(facts: List<StageFact>) {
        facts.filter { it.stage == LegislativeStage.UNKNOWN }
            .forEach { meters.counter("legislative.stage.unmapped", "label", it.sourceLabel).increment() }
    }

    private fun skip(reason: String, process: String) {
        meters.counter("legislative.process.skipped", "reason", reason).increment()
        log.debug("Process {} not projected as a draft ({})", process, reason)
    }

    private companion object {
        val PROCESS = DocumentKind("process")
    }
}
