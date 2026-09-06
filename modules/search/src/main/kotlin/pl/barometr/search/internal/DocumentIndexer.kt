package pl.barometr.search.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentTextExtracted
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore

/**
 * Puts what a document says into the index, as soon as it says it.
 *
 * The event carries the hash of the extracted text and not the text, which is exactly
 * what it was designed for: a consumer that fetches the blob gets the same characters
 * every other claim in the system is measured against, and the publication register
 * stays small enough to be a queue rather than a second copy of the corpus.
 *
 * **Which draft the file belongs to is not written here.** That link is made by another
 * listener on another event, and a file commonly reaches the archive before the card
 * that creates its draft — so an entry that recorded the draft at this moment would
 * record "none" for most files and never be asked again. The link is read at query
 * time instead, from the context that owns it, where it is always as current as the
 * database.
 *
 * Failure here is not failure of anything upstream: the register redelivers a listener
 * that threw, so an index that was briefly unreachable catches up on its own.
 */
@Service
class DocumentIndexer(
    private val documents: DocumentCatalog,
    private val blobs: BlobStore,
    private val entries: DocumentEntries,
    private val writer: LegislativeIndexWriter,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun indexDocumentText(extracted: DocumentTextExtracted) {
        val document = documents.documentById(extracted.documentId) ?: return
        if (document.kind !in SearchableDocuments) return

        val text = blobs.read(BlobBucket.DERIVED, extracted.textHash)?.use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        if (text == null) {
            // The text is derived and the derived bucket is the one that may be thrown
            // away, so this is a gap to notice rather than an error to fail on: a
            // rebuild after re-extraction fills it.
            meters.counter("search.document.unindexed", "reason", "text-missing").increment()
            log.warn("No extracted text at {} for document {}", extracted.textHash, document.externalId)
            return
        }

        writer.write(LegislativeIndex.ALIAS, entries.entryOf(document, text))
        meters.counter("search.document.indexed").increment()
    }
}
