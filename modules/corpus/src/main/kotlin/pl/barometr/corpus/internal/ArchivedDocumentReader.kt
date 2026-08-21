package pl.barometr.corpus.internal

import pl.barometr.ingestion.api.ExternalId
import pl.barometr.sources.api.ConnectorId

/**
 * Reads one source's archived payload into the little corpus needs to know about it.
 *
 * Interpretation lives here rather than in the connector on purpose. This system
 * promises to archive exactly what a source returned and derive everything else from
 * that archive; if the connector described the document as it fetched it, rebuilding
 * the corpus would mean fetching everything again. A connector knows how to *address*
 * a document, a reader knows how to *read* one, and only the second has to keep
 * working when the source is gone.
 *
 * The price is that the external-id format is stated twice — here and in the
 * connector's `*ExternalIds` — and that is the deliberate half of the trade: the
 * archive is the contract between them, so this side must be able to stand on the
 * stored bytes alone.
 */
interface ArchivedDocumentReader {

    val connectorId: ConnectorId

    /**
     * Never fails and never returns nothing: a payload whose shape this reader
     * cannot place is still a document with an identity and a version, and
     * [pl.barometr.corpus.api.DocumentKind.UNKNOWN] says so out loud.
     */
    fun describe(externalId: ExternalId, payload: ByteArray): DocumentDescriptor
}
