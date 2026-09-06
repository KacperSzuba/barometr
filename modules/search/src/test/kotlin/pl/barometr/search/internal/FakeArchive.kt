package pl.barometr.search.internal

import pl.barometr.corpus.api.ArchivedDocument
import pl.barometr.corpus.api.ArchivedVersion
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentTextExtracted
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import java.time.Instant

/**
 * What corpus would answer, over a real blob store.
 *
 * The text is genuinely written to storage and addressed by its hash, because that is
 * the half of the arrangement being tested: the event carries a hash and the indexer
 * has to be able to turn it back into characters.
 */
class FakeArchive(private val blobs: BlobStore) : DocumentCatalog {

    private val documents = linkedMapOf<DocumentId, ArchivedDocument>()
    private val versions = linkedMapOf<DocumentId, ArchivedVersion>()

    /** Archives a document with its text, and answers with what corpus would announce. */
    fun archive(kind: DocumentKind, externalId: String, title: String?, text: String): DocumentTextExtracted {
        val documentId = DocumentId(Ids.next())
        val versionId = DocumentVersionId(Ids.next())
        val address = ExternalId(externalId)
        val stored = blobs.store(BlobBucket.DERIVED, text.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")

        documents[documentId] = ArchivedDocument(documentId, address, kind, title, publishedAt = null)
        versions[documentId] = ArchivedVersion(
            documentId = documentId,
            externalId = address,
            versionId = versionId,
            contentHash = ContentHash.of(text.toByteArray(Charsets.UTF_8)),
            textHash = stored.contentHash,
        )

        return DocumentTextExtracted(
            documentId = documentId,
            versionId = versionId,
            textHash = stored.contentHash,
            textLength = text.length,
            chunkCount = 1,
            occurredAt = Instant.parse("2026-08-21T10:00:00Z"),
        )
    }

    override fun documentById(id: DocumentId): ArchivedDocument? = documents[id]

    override fun latestVersionAt(externalId: ExternalId): ArchivedVersion? =
        versions.values.firstOrNull { it.externalId == externalId }

    override fun versionsOfKind(kind: DocumentKind, after: DocumentId?, limit: Int): List<ArchivedVersion> =
        versions.values
            .filter { documents[it.documentId]?.kind == kind }
            .filter { after == null || it.documentId.value > after.value }
            .sortedBy { it.documentId.value }
            .take(limit)

    override fun countByKind(): Map<DocumentKind, Int> =
        documents.values.groupingBy { it.kind }.eachCount()
}
