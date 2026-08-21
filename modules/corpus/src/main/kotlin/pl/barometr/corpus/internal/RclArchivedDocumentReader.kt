package pl.barometr.corpus.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.sources.api.ConnectorId

/**
 * Reads RPL's archived pages.
 *
 * Address only. RPL publishes no API, so its payloads are whole HTML pages, and the
 * title on a draft's card is reachable only through the selectors the connector
 * keeps in configuration. Re-implementing that parse here would put the site's
 * layout in two places and make a layout change break in two — so a draft gets its
 * identity and its version chain now, which is what the archive needs, and its title
 * when RPL's structural extraction is written.
 *
 * The version chain is the point regardless: a draft's page is re-fetched every six
 * hours, and content addressing turns that into a new version exactly when the
 * ministry actually changed something.
 */
@Component
class RclArchivedDocumentReader : ArchivedDocumentReader {

    private val log = LoggerFactory.getLogger(javaClass)

    override val connectorId = ConnectorId("rcl")

    override fun describe(externalId: ExternalId, payload: ByteArray): DocumentDescriptor {
        val kind = when (val id = externalId.value) {
            in CATALOG_CHANGE_REGISTER -> CATALOG_CHANGE_REGISTER_KIND
            in CHANGE_REGISTER -> CHANGE_REGISTER_KIND
            in CATALOG -> CATALOG_KIND
            in PROJECT -> PROJECT_KIND
            else -> {
                log.warn("RPL document '{}' matches no known address shape", id)
                DocumentKind.UNKNOWN
            }
        }

        return DocumentDescriptor(kind, title = null, publishedAt = null)
    }

    private operator fun Regex.contains(value: String): Boolean = matches(value)

    private companion object {
        // The archive's addressing contract, stated in `RclExternalIds` on the
        // ingestion side. Duplicated here deliberately: see ArchivedDocumentReader.
        val PROJECT = Regex("projekt/[^/]+/[^/]+")
        val CHANGE_REGISTER = Regex("projekt/[^/]+/[^/]+/rejestr")
        val CATALOG = Regex("projekt/[^/]+/[^/]+/katalog/[^/]+")
        val CATALOG_CHANGE_REGISTER = Regex("projekt/[^/]+/[^/]+/katalog/[^/]+/rejestr")

        val PROJECT_KIND = DocumentKind("rcl-project")
        val CHANGE_REGISTER_KIND = DocumentKind("rcl-change-register")
        val CATALOG_KIND = DocumentKind("rcl-catalog")
        val CATALOG_CHANGE_REGISTER_KIND = DocumentKind("rcl-catalog-change-register")
    }
}
