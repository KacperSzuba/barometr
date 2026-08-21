package pl.barometr.ingestion.internal

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import pl.barometr.ingestion.api.PayloadMediaTypes
import pl.barometr.ingestion.api.RawDocumentIngested
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.api.SinkOutcome
import pl.barometr.sources.api.SourceId
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Archiving policy for one payload: store the bytes, record the document, announce
 * it if it was new.
 *
 * This is where the order matters and why it is stated once rather than at every
 * call site. Blob first, row second: content addressing makes the write idempotent,
 * so a crash between the two leaves an unreferenced blob — harmless and collectable
 * — whereas the reverse order would leave a row pointing at bytes that do not exist.
 */
@Service
class RawDocumentArchiver(
    private val blobs: BlobStore,
    private val documents: RawDocumentRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {

    fun archive(sourceId: SourceId, runId: UUID?, payload: RawPayload): SinkOutcome {
        val stored = blobs.store(
            bucket = BlobBucket.RAW,
            payload = payload.payload,
            mediaType = PayloadMediaTypes.of(payload.kind),
        )

        val documentId = documents.insertIfAbsent(
            NewRawDocument(
                sourceId = sourceId,
                externalId = payload.externalId,
                contentHash = stored.contentHash,
                blobKey = blobs.keyOf(stored.contentHash),
                payloadKind = payload.kind,
                etag = payload.etag,
                lastModified = payload.lastModified,
                runId = runId,
            ),
        ) ?: return SinkOutcome.ALREADY_KNOWN

        // Only genuinely new content starts the pipeline. This one line is why
        // replaying a connector is cheap instead of re-running extraction,
        // embedding and alerting across the whole archive.
        events.publishEvent(
            RawDocumentIngested(
                rawDocumentId = documentId,
                sourceId = sourceId,
                externalId = payload.externalId,
                contentHash = stored.contentHash,
                kind = payload.kind,
                occurredAt = clock.instant(),
            ),
        )

        return SinkOutcome.STORED
    }
}
