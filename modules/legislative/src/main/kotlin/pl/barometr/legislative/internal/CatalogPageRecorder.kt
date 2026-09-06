package pl.barometr.legislative.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.shared.ContentHash
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore

/**
 * What a stage's catalog page says is inside it: the folders, and the files.
 *
 * RPL renders a stage's whole subtree inline, and that makes this page the only place
 * two facts are ever stated. A filed letter is said to belong to the stage above it
 * here and nowhere else, which is the edge a consultation deadline is matched along;
 * and a file is given a name, an author and a filing date here and nowhere else — the
 * archived file itself is bytes under an address.
 *
 * Written once and called twice, because both routes into the archive need it: the
 * listener that fires when a page is stored, and the walk that goes back over the pages
 * stored before either of them existed.
 */
@Service
class CatalogPageRecorder(
    private val blobs: BlobStore,
    private val pages: RclPageReader,
    private val consultations: ConsultationRepository,
    private val filings: DraftFilingRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * True when the page was read, which is what a walk's budget counts.
     *
     * False for an address that is not a catalog's and for bytes the archive has lost —
     * neither is a failure to retry, and the second is a warning with an address in it.
     */
    fun recordWhatIsFiledIn(externalId: ExternalId, contentHash: ContentHash): Boolean {
        val catalog = RclCatalogAddress.ofCatalogPage(externalId) ?: return false

        val payload = blobs.read(BlobBucket.RAW, contentHash)?.use { it.readBytes() }
        if (payload == null) {
            log.warn("No archived bytes for RPL catalog {} at {}", externalId, contentHash)
            return false
        }

        val page = pages.readCatalog(payload)
        page.childDirectories.forEach { consultations.recordFolder(it.catalogId, catalog.catalogId) }
        // The whole subtree, not only what sits directly in this folder: RPL renders a
        // stage's files inline under it, and a file that appears on both this page and
        // its child's is the same row twice, keyed by the id RPL gives it.
        page.documents.forEach { filings.recordFiling(catalog.projectId, it) }

        log.debug(
            "Catalog {} holds {} folders and {} files",
            catalog.catalogId,
            page.childDirectories.size,
            page.documents.size,
        )

        return true
    }

    companion object {
        /**
         * The kind corpus files these pages under, named once for the two callers that
         * select on it. `RclArchivedDocumentReader` decides it from the address, which
         * is the contract both sides stand on.
         */
        val CATALOG = DocumentKind("rcl-catalog")
    }
}
