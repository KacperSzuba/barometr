package pl.barometr.legislative.internal

import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.DocumentVersionRecorded

/**
 * Reads a stage's catalog page the moment it is archived.
 *
 * Every catalog page is read, not only the ones a consultation was opened on, and
 * `legislative.catalog_folder` says why: these listeners run concurrently, so the page
 * can be derived before the card that opens the consultation, and an edge recorded only
 * for consultations already open would be lost on that ordering. The filings recorded
 * beside it are keyed by RPL's project id for the same reason — a page read before its
 * draft exists is a page whose files are still recorded.
 *
 * What is done with the page is [CatalogPageRecorder]'s; this is the edge of the
 * archive, and [ArchivedCatalogSweep] is the way back into it.
 */
@Service
class RclCatalogProjector(private val catalogs: CatalogPageRecorder) {

    @ApplicationModuleListener
    fun recordWhatTheCatalogHolds(recorded: DocumentVersionRecorded) {
        if (recorded.kind != CatalogPageRecorder.CATALOG) return

        catalogs.recordWhatIsFiledIn(recorded.externalId, recorded.contentHash)
    }
}
