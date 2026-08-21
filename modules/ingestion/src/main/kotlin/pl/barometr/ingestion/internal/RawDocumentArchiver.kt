package pl.barometr.ingestion.internal

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
 *
 * One payload is one transaction, and that is not bookkeeping. Everything listening
 * to [RawDocumentIngested] is an `@ApplicationModuleListener`, which runs *after the
 * commit* of the transaction the event was published in — so published outside one,
 * the event is registered and then never delivered. The whole derivation chain
 * stopped there silently: eight thousand documents archived, eight thousand
 * publications recorded, none of them ever handled, and nothing in the logs to say so.
 *
 * The blob write sits inside the boundary, which costs nothing against a filesystem
 * store and is worth revisiting when that becomes S3 — a network call inside a
 * transaction is exactly what this codebase tells itself not to do.
 */
@Service
class RawDocumentArchiver(
    private val blobs: BlobStore,
    private val documents: RawDocumentRepository,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {

    @Transactional
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
