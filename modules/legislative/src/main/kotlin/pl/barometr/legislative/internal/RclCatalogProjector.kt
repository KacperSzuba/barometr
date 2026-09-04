package pl.barometr.legislative.internal

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore

/**
 * Records which folders a stage's catalog page says are inside it.
 *
 * The edge a deadline is matched along. RPL addresses a filed document by the folder
 * it sits in and nothing above it, so the letter opening a consultation arrives naming
 * "Pisma kierujące projekt do konsultacji publicznych" and no consultation of any
 * kind; the page read here is the only place that folder is ever said to belong to the
 * stage the consultation was opened on.
 *
 * Every catalog page is read, not only the consultation ones, and
 * `legislative.catalog_folder` says why: these listeners run concurrently, so the page
 * can be derived before the card that opens the consultation, and an edge recorded
 * only for consultations already open would be lost on that ordering.
 */
@Service
class RclCatalogProjector(
    private val blobs: BlobStore,
    private val pages: RclPageReader,
    private val consultations: ConsultationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun recordFoldersInsideCatalog(recorded: DocumentVersionRecorded) {
        if (recorded.kind != CATALOG) return

        val catalog = RclCatalogAddress.ofCatalogPage(recorded.externalId) ?: return
        val payload = blobs.read(BlobBucket.RAW, recorded.contentHash)?.use { it.readBytes() }
        if (payload == null) {
            log.warn("No archived bytes for RPL catalog {} at {}", recorded.externalId, recorded.contentHash)
            return
        }

        val folders = pages.readCatalog(payload).childDirectories
        folders.forEach { consultations.recordFolder(it.catalogId, catalog.catalogId) }

        log.debug("Catalog {} holds {} folders", catalog.catalogId, folders.size)
    }

    private companion object {
        val CATALOG = DocumentKind("rcl-catalog")
    }
}
