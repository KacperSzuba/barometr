package pl.barometr.corpus.internal.text

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.DocumentTextExtracted
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.corpus.internal.BlobRepository
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import java.time.Clock

/**
 * Turns an archived version into the text it says, and the chunks that text is cited
 * in.
 *
 * The step every downstream promise waits on. A summary that cites a character range,
 * a diff between two versions of a bill, an embedding of a paragraph — none of them
 * can begin from a PDF, and all of them begin from here.
 *
 * It listens rather than being called, through [ApplicationModuleListener] and for the
 * same reason [pl.barometr.corpus.internal.ArchivedDocumentRecorder] does: the
 * publication is written in the recorder's transaction, this runs in its own, and a
 * failure here leaves a row Spring Modulith redelivers rather than a version that
 * silently never got its text.
 *
 * Redelivery is safe because the claim is the database's: the update takes the version
 * only if nothing else has, and the chunks are written by whoever won. The text blob
 * is content-addressed, so storing it twice stores it once.
 *
 * **The original is never re-derived from the text.** The text goes to the derived
 * bucket, which exists to be thrown away and recomputed; the bytes a source served
 * stay in the raw bucket untouched. That is what makes changing the chunk size, or the
 * parser, a re-run rather than a re-crawl.
 */
@Service
class DocumentTextExtractor(
    private val blobs: BlobStore,
    private val blobIndex: BlobRepository,
    private val texts: DocumentTextRepository,
    private val extraction: PlainTextExtraction,
    private val chunker: TextChunker,
    private val events: ApplicationEventPublisher,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun extractDocumentText(recorded: DocumentVersionRecorded) {
        val payload = blobs.read(BlobBucket.RAW, recorded.contentHash)?.use { it.readBytes() }
            ?: return skip("payload-missing", recorded, "the archive has no bytes at ${recorded.contentHash}")

        val extracted = try {
            extraction.readPlainText(payload)
        } catch (unreadable: Exception) {
            // Every parser failure, including the ones Tika wraps from a corrupt
            // file. Caught rather than propagated because a payload this cannot read
            // will not become readable on the fourth redelivery, and the bytes are
            // still in the archive for a parser that can.
            return skip("unreadable", recorded, "${unreadable.javaClass.simpleName}: ${unreadable.message}")
        }

        if (extracted.isEmpty) {
            // A scan with no text layer. Counted separately because it is the one
            // reason on this list that OCR would fix, and the size of that pile is
            // what decides whether OCR is worth building.
            return skip("no-text", recorded, "parsed as ${extracted.mediaType} and said nothing")
        }

        record(recorded, extracted.text)
    }

    private fun record(recorded: DocumentVersionRecorded, text: String) {
        val stored = blobs.store(BlobBucket.DERIVED, text.toByteArray(Charsets.UTF_8), TEXT_MEDIA_TYPE)
        blobIndex.recordStoredBlob(
            contentHash = stored.contentHash,
            byteSize = stored.byteSize,
            mediaType = TEXT_MEDIA_TYPE,
            bucket = BlobBucket.DERIVED,
        )

        val chunks = chunker.chunk(text)

        // False means another delivery got there first, which is the normal outcome of
        // a redelivery. Nothing to announce, because nothing changed.
        val recordedNow = texts.recordExtractedText(
            versionId = recorded.versionId,
            textHash = stored.contentHash,
            textLength = text.length,
            chunks = chunks,
        )
        if (!recordedNow) return

        log.debug(
            "Extracted {} characters in {} chunks from {} ({})",
            text.length,
            chunks.size,
            recorded.externalId,
            recorded.connectorId,
        )

        events.publishEvent(
            DocumentTextExtracted(
                documentId = recorded.documentId,
                versionId = recorded.versionId,
                textHash = stored.contentHash,
                textLength = text.length,
                chunkCount = chunks.size,
                occurredAt = clock.instant(),
            ),
        )
    }

    /**
     * A version that has no text, said out loud twice.
     *
     * The log line names the one document so it can be found; the counter makes a
     * pattern of them visible on a dashboard, which a log line cannot. Nothing is lost
     * either way — the payload is still in the archive, and the archive is what the
     * text is derived from, so a parser taught the format later can still reach it.
     */
    private fun skip(reason: String, recorded: DocumentVersionRecorded, detail: String) {
        log.warn("No text from {} ({}): {}", recorded.externalId, reason, detail)
        meters.counter(
            "corpus.text.unextracted",
            "reason", reason,
            "source", recorded.connectorId.value,
        ).increment()
    }

    private companion object {
        /**
         * The charset is part of the media type because the offsets are not: they
         * count characters, and a reader that decodes these bytes as anything but
         * UTF-8 gets different ones.
         */
        const val TEXT_MEDIA_TYPE = "text/plain; charset=utf-8"
    }
}
