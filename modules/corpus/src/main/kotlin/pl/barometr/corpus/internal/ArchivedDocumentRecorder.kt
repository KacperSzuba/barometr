package pl.barometr.corpus.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.ingestion.api.PayloadMediaTypes
import pl.barometr.ingestion.api.RawDocumentIngested
import pl.barometr.sources.api.SourceDefinition
import pl.barometr.sources.api.SourceRegistry
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import java.time.Clock

/**
 * Turns an archived payload into a document and a version of it.
 *
 * The step the whole "archive first, derive later" promise rests on: ingestion says
 * *these bytes are new*, and this says *they are version three of this document* —
 * the first statement anything downstream can act on.
 *
 * It listens rather than being called, and through
 * [ApplicationModuleListener] rather than a plain event listener, so the derivation
 * is an outbox: the publication is written in ingestion's transaction, this runs in
 * its own, and a failure here leaves a row Spring Modulith redelivers instead of a
 * document that silently never appeared.
 *
 * Everything it does is idempotent, which is what makes redelivery safe. The blob row
 * collides on its own hash, the document upserts on its address, and the version is
 * refused by the unique index if that content is already held — so the second
 * delivery of an event archives nothing and publishes nothing.
 *
 * The payload is read before the first statement, because the writes are ordered:
 * blob row, document, version. A version may not reference bytes the database has not
 * been told about, and the foreign key says so.
 */
@Service
class ArchivedDocumentRecorder(
    private val sources: SourceRegistry,
    private val readers: ArchivedDocumentReaders,
    private val blobs: BlobStore,
    private val blobIndex: BlobRepository,
    private val documents: DocumentRepository,
    private val events: ApplicationEventPublisher,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun recordDocumentVersion(ingested: RawDocumentIngested) {
        val source = sources.byId(ingested.sourceId)
            ?: return skip("unknown-source", "no source ${ingested.sourceId} for ${ingested.externalId}")

        val reader = readers.forConnector(source.connectorId)
            ?: return skip(
                "no-reader",
                "nothing can read ${source.connectorId}'s archive, so ${ingested.externalId} stays raw",
                source,
            )

        val payload = blobs.read(BlobBucket.RAW, ingested.contentHash)?.use { it.readBytes() }
            ?: return skip(
                "payload-missing",
                "the archive has no bytes for ${ingested.externalId} at ${ingested.contentHash}",
                source,
            )

        record(ingested, source, reader.describe(ingested.externalId, payload), payload.size.toLong())
    }

    private fun record(
        ingested: RawDocumentIngested,
        source: SourceDefinition,
        descriptor: DocumentDescriptor,
        byteSize: Long,
    ) {
        blobIndex.recordStoredBlob(
            contentHash = ingested.contentHash,
            byteSize = byteSize,
            mediaType = PayloadMediaTypes.of(ingested.kind),
            bucket = BlobBucket.RAW,
        )

        val documentId = documents.documentFor(
            sourceId = source.id,
            externalId = ingested.externalId,
            kind = descriptor.kind,
            title = descriptor.title,
        )

        // Null means this content is already a version of this document, which is the
        // normal outcome of a redelivery and of any connector replay. Nothing to
        // announce, because nothing changed.
        val version = documents.appendVersionIfNew(
            documentId = documentId,
            rawDocumentId = ingested.rawDocumentId,
            contentHash = ingested.contentHash,
            publishedAt = descriptor.publishedAt,
        ) ?: return

        log.debug(
            "Recorded version {} of {} ({})",
            version.versionNo,
            ingested.externalId,
            source.connectorId,
        )

        events.publishEvent(
            DocumentVersionRecorded(
                documentId = documentId,
                versionId = version.id,
                sourceId = source.id,
                connectorId = source.connectorId,
                externalId = ingested.externalId,
                kind = descriptor.kind,
                contentHash = ingested.contentHash,
                versionNo = version.versionNo,
                occurredAt = clock.instant(),
            ),
        )
    }

    /**
     * A payload that could not become a document, said out loud twice.
     *
     * The log line names the one document so it can be found; the counter makes a
     * pattern of them visible on a dashboard, which a log line cannot. Nothing is
     * lost either way — the bytes are still in the archive, and the archive is what
     * the corpus is derived from, so a reader written later can still reach them.
     */
    private fun skip(reason: String, detail: String, source: SourceDefinition? = null) {
        log.warn("Not derived into corpus ({}): {}", reason, detail)
        meters.counter(
            "corpus.documents.underived",
            "reason", reason,
            "source", source?.connectorId?.value ?: "unknown",
        ).increment()
    }
}
