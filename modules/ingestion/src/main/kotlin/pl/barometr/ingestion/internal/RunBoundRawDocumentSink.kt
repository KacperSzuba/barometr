package pl.barometr.ingestion.internal

import pl.barometr.ingestion.api.RawDocumentSink
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.ingestion.api.SinkOutcome
import pl.barometr.sources.api.SourceId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * The connector's view of ingestion: accept a payload, note a shape warning.
 *
 * Everything it does is delegated — archiving to [RawDocumentArchiver]. What is
 * left here is the run's bookkeeping, which is the one thing that genuinely belongs
 * to a single run rather than to the module.
 *
 * Thread-safe, because a connector is free to fan a run out across virtual threads
 * and nothing in the SPI says otherwise. The counters are atomic for the same
 * reason the warning list is copy-on-write; previously the list was guarded and the
 * counters were not, which meant one of the two was wrong whichever way a connector
 * chose to behave.
 */
class RunBoundRawDocumentSink internal constructor(
    private val archiver: RawDocumentArchiver,
    private val sourceId: SourceId,
    private val runId: UUID?,
) : RawDocumentSink {

    private val collectedWarnings = CopyOnWriteArrayList<SchemaWarning>()
    private val seen = AtomicInteger()
    private val stored = AtomicInteger()

    /** Written to `source_run` when the run finishes. */
    val schemaWarnings: List<SchemaWarning> get() = collectedWarnings.toList()

    /** Payloads handed over, whether or not they turned out to be new. */
    val documentsSeen: Int get() = seen.get()

    /** Of those, the ones whose content the archive did not already hold. */
    val documentsStored: Int get() = stored.get()

    override fun archive(payload: RawPayload): SinkOutcome {
        seen.incrementAndGet()
        val outcome = archiver.archive(sourceId, runId, payload)
        if (outcome == SinkOutcome.STORED) stored.incrementAndGet()
        return outcome
    }

    override fun recordSchemaWarning(warning: SchemaWarning) {
        collectedWarnings += warning
    }
}
