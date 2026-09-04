package pl.barometr.corpus.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.sources.api.ConnectorId

/**
 * Reads RPL's archived pages.
 *
 * The kind comes from the address, as everywhere; the title comes from the page,
 * through the port the connector publishes for exactly this. RPL has no API, so its
 * payloads are whole HTML pages and reading one needs the selectors that describe the
 * site — re-implementing that here would put its layout in two modules and break it in
 * two the next time it is redesigned.
 *
 * Only a draft's card carries a title. A change register, a stage catalog or a filed
 * document is a page about a draft rather than the draft itself, and inventing a title
 * for it would put something in the corpus that nobody wrote — a filed document does
 * have a name, but it is the file's, printed on the catalog page rather than in the
 * bytes, and it reaches the corpus nowhere.
 *
 * The version chain is the point regardless: a draft's page is re-fetched every six
 * hours, and content addressing turns that into a new version exactly when the
 * ministry actually changed something.
 */
@Component
class RclArchivedDocumentReader(private val pages: RclPageReader) : ArchivedDocumentReader {

    private val log = LoggerFactory.getLogger(javaClass)

    override val connectorId = ConnectorId("rcl")

    override fun describe(externalId: ExternalId, payload: ByteArray): DocumentDescriptor {
        val kind = when (val id = externalId.value) {
            in FILED_DOCUMENT -> FILED_DOCUMENT_KIND
            in CATALOG_CHANGE_REGISTER -> CATALOG_CHANGE_REGISTER_KIND
            in CHANGE_REGISTER -> CHANGE_REGISTER_KIND
            in CATALOG -> CATALOG_KIND
            in PROJECT -> PROJECT_KIND
            else -> {
                log.warn("RPL document '{}' matches no known address shape", id)
                DocumentKind.UNKNOWN
            }
        }

        // A card names itself; the pages beneath it do not.
        val title = if (kind == PROJECT_KIND) pages.readProjectCard(payload)?.title?.takeIf { it.isNotBlank() } else null

        return DocumentDescriptor(kind, title = title, publishedAt = null)
    }

    private operator fun Regex.contains(value: String): Boolean = matches(value)

    private companion object {
        // The archive's addressing contract, stated in `RclExternalIds` on the
        // ingestion side. Duplicated here deliberately: see ArchivedDocumentReader.
        val PROJECT = Regex("projekt/[^/]+/[^/]+")
        val CHANGE_REGISTER = Regex("projekt/[^/]+/[^/]+/rejestr")
        val CATALOG = Regex("projekt/[^/]+/[^/]+/katalog/[^/]+")
        val CATALOG_CHANGE_REGISTER = Regex("projekt/[^/]+/[^/]+/katalog/[^/]+/rejestr")

        /**
         * The files themselves: a draft's text, its justification, an impact
         * assessment, the letters sending it out for comment, the tables of comments
         * that came back. Everything the corpus is actually made of on this source —
         * the four shapes above are pages *about* a draft, and this is the draft.
         */
        val FILED_DOCUMENT = Regex("projekt/[^/]+/[^/]+/katalog/[^/]+/dokument/[^/]+")

        val PROJECT_KIND = DocumentKind("rcl-project")
        val CHANGE_REGISTER_KIND = DocumentKind("rcl-change-register")
        val CATALOG_KIND = DocumentKind("rcl-catalog")
        val CATALOG_CHANGE_REGISTER_KIND = DocumentKind("rcl-catalog-change-register")

        /** Named for what the published page model calls one: a document filed in a folder. */
        val FILED_DOCUMENT_KIND = DocumentKind("rcl-filed-document")
    }
}
