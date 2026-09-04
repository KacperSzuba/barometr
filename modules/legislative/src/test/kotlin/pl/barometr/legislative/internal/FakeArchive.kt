package pl.barometr.legislative.internal

import pl.barometr.corpus.api.ArchivedDocument
import pl.barometr.corpus.api.ArchivedVersion
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids

/**
 * The corpus, as much of it as a sweep needs: what is at an address, and what is held
 * of a kind.
 *
 * Addressed the way a caller going back over the archive addresses it — by the id the
 * source knows a document by — because that is the only id such a caller can
 * reconstruct. [lookups] records what was asked for, which is how the tests that care
 * about the sweep *not* doing work say so.
 */
class FakeArchive : DocumentCatalog {
    private val versions = linkedMapOf<String, ArchivedVersion>()
    private val kinds = mutableMapOf<String, DocumentKind>()
    private val asked = mutableListOf<String>()

    val lookups: List<String> get() = asked.toList()

    fun holds(
        address: String,
        kind: DocumentKind = DocumentKind.UNKNOWN,
        contentHash: ContentHash? = null,
        textHash: ContentHash? = null,
    ) {
        kinds[address] = kind
        versions[address] = ArchivedVersion(
            // Kept across restatements of the same address: a second call is a new
            // version of one document, not a second document.
            documentId = versions[address]?.documentId ?: DocumentId(Ids.next()),
            externalId = ExternalId(address),
            versionId = DocumentVersionId(Ids.next()),
            contentHash = contentHash ?: ContentHash.of(address.toByteArray()),
            textHash = textHash,
        )
    }

    fun clear() {
        versions.clear()
        kinds.clear()
        asked.clear()
    }

    override fun documentById(id: DocumentId): ArchivedDocument? = null

    override fun latestVersionAt(externalId: ExternalId): ArchivedVersion? {
        asked += externalId.value

        return versions[externalId.value]
    }

    /**
     * Insertion order stands in for the identifier order the real one pages by: both
     * are the order the archive stored them in, which is what makes a keyset walk
     * finish.
     */
    override fun versionsOfKind(kind: DocumentKind, after: DocumentId?, limit: Int): List<ArchivedVersion> {
        val ofKind = versions.values.filter { kinds[it.externalId.value] == kind }
        val remaining = after?.let { id -> ofKind.dropWhile { it.documentId != id }.drop(1) } ?: ofKind

        return remaining.take(limit)
    }

    override fun countByKind(): Map<DocumentKind, Int> = emptyMap()
}
