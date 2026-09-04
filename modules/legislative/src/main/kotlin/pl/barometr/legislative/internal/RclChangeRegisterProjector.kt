package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.connectors.rcl.api.RclChange
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.legislative.api.DraftId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import java.time.Clock
import java.time.ZoneId

/**
 * Turns a draft's event log into the dated timeline its card could never support.
 *
 * A card is a checklist: eight stages with a state each and, on the ones that have
 * moved, a "last modified" stamp — which is not the day a stage began, so
 * [RclCardProjector] records one coarse fact and says so. This page is the other half
 * of the same source and the only one on it that times anything to the minute: the
 * draft moved to "3. Konsultacje publiczne" at 15:26 on the ninth of April, in RPL's
 * own words.
 *
 * **A period runs from one transition to the next.** The register states beginnings and
 * never endings, which is the same thing said differently: a draft leaves a stage by
 * arriving at another, and the last stage on the list is where it still is. So the
 * final period is left open, and it closes when a later reading of this same register
 * shows what came after — recorded as a new fact beside the open one, because
 * `stage_transition` is append-only and that is what makes "what did we believe on
 * Tuesday" answerable.
 *
 * Nothing here records who made a change. RPL names civil servants in these logs, and
 * what this system retains of that is a decision belonging with the source's legal
 * basis rather than with a derivation that has no use for it.
 */
@Service
class RclChangeRegisterProjector(
    private val blobs: BlobStore,
    private val pages: RclPageReader,
    private val drafts: DraftIdentifierRepository,
    private val transitions: StageTransitionRepository,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun projectStageTimeline(recorded: DocumentVersionRecorded) {
        if (recorded.kind != CHANGE_REGISTER) return

        val projectId = RclCatalogAddress.projectInChangeRegister(recorded.externalId) ?: return
        val draftId = drafts.draftFor(DraftIdentifierScheme.RCL_PROJECT, projectId) ?: return

        val payload = blobs.read(BlobBucket.RAW, recorded.contentHash)?.use { it.readBytes() }
        if (payload == null) {
            log.warn("No archived bytes for RPL register {} at {}", recorded.externalId, recorded.contentHash)
            return
        }

        record(draftId, pages.readChangeRegister(payload).stageTransitions, recorded.versionId)
    }

    private fun record(draftId: DraftId, moves: List<RclChange>, statedBy: DocumentVersionId) {
        val dated = moves.filter { it.occurredAt != null }
        if (dated.isEmpty()) return

        val facts = dated.mapIndexed { ordinal, move ->
            val label = move.newValue.orEmpty().ifBlank { move.description }

            StageFact(
                stage = RclStageVocabulary.stageOf(label) ?: LegislativeStage.UNKNOWN,
                from = move.occurredAt!!.atZone(WARSAW).toInstant(),
                // The next move is this one's end, and the last has none: a draft is
                // still where the register last put it.
                until = dated.getOrNull(ordinal + 1)?.occurredAt?.atZone(WARSAW)?.toInstant(),
                ordinal = ordinal,
                sourceLabel = label,
                // RPL states a checklist, not an expected order, and its own pages show
                // stages skipped and revisited. There is nothing here for this model to
                // be surprised by.
                isException = false,
            )
        }

        val recorded = transitions.recordFacts(draftId, facts, statedBy, clock.instant())

        meters.counter("legislative.rcl_register.stages_recorded").increment(recorded.toDouble())
        if (recorded > 0) log.debug("Recorded {} dated stages of draft {} from RPL's register", recorded, draftId)
    }

    private companion object {
        val CHANGE_REGISTER = DocumentKind("rcl-change-register")

        /**
         * RPL writes local time with no offset. Read as Warsaw's, which is where the
         * ministry making the change was sitting; read as UTC it would put every
         * afternoon transition an hour or two early, and one or two hours is the
         * difference between two stages on a busy day.
         */
        val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
    }
}
