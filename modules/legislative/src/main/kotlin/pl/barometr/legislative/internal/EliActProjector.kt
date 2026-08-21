package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.sources.api.ConnectorId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore

/**
 * Turns a published act into the identity every other source is matched against.
 *
 * This is where ELI becomes the canonical key of the whole system. The act gets a row,
 * its ELI an identifier, its change references an edge each — and, the part that makes
 * the rest of the product possible, every Sejm print the register names is pinned to
 * it. That link is stated by the publisher rather than inferred by us, which is why
 * most documents end up matched without anyone comparing a title.
 *
 * It reads the archived payload rather than being handed the act's fields, for the
 * same reason the corpus does: the graph has to be rebuildable from stored bytes
 * alone. Everything it writes is keyed on the ELI, so redelivery restates rather than
 * duplicates.
 */
@Service
class EliActProjector(
    private val blobs: BlobStore,
    private val reader: EliActReader,
    private val acts: ActRepository,
    private val identifiers: ActIdentifierRepository,
    private val references: ActReferenceRepository,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun projectPublishedAct(recorded: DocumentVersionRecorded) {
        if (recorded.connectorId != ISAP) return

        val payload = blobs.read(BlobBucket.RAW, recorded.contentHash)?.use { it.readBytes() }
        if (payload == null) {
            log.warn("No archived bytes for act {} at {}", recorded.externalId, recorded.contentHash)
            return
        }

        val act = reader.read(payload)
        if (act == null) {
            log.warn("Archived document {} does not describe an act", recorded.externalId)
            return
        }

        val actId = acts.actFor(act)
        identifiers.pointAtAct(IdentifierScheme.ELI, act.eli.value, actId, MatchMethod.EXACT, confidence = 1.0)

        // Stated by the publisher on the act itself, so it outranks anything a title
        // comparison could conclude — and it overwrites a fuzzy link made earlier.
        act.prints.forEach { print ->
            identifiers.pointAtAct(
                IdentifierScheme.SEJM_PRINT,
                print.documentAddress,
                actId,
                MatchMethod.EXACT,
                confidence = 1.0,
            )
        }

        references.replaceReferencesFrom(act.eli, act.references, recorded.versionId)
        recordUnmappedLabels(act)

        log.debug("Projected act {} with {} references", act.eli, act.references.size)
    }

    /**
     * A reference label this system will not assert a direction for is counted, not
     * logged: they arrive by the thousand and the useful question is which labels and
     * how many, which a counter answers and a log line buries. The tag is a label from
     * the register's own closed vocabulary — about a dozen of them — so the series
     * count stays bounded.
     */
    private fun recordUnmappedLabels(act: EliActMetadata) {
        act.unmappedLabels.forEach { label ->
            meters.counter("legislative.reference.unmapped", "label", label).increment()
        }
    }

    private companion object {
        val ISAP = ConnectorId("isap")
    }
}
