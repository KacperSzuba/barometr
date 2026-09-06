package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentId

/**
 * Reads the catalog pages that were archived before anything read them.
 *
 * [RclCatalogProjector] fires when a page is stored, and a page is stored when its
 * bytes are new — so a stage nobody has touched since the archive first fetched it will
 * never fire again, and what a ministry filed under it stays unread for as long as the
 * draft is dormant, which for most drafts is for ever. On an archive that has been
 * filling for months that is nearly all of it.
 *
 * **The walk remembers where it got to, and that is the whole design.** The other
 * sweeps here resume from a marker on the thing being derived, and this one cannot: a
 * stage folder with no files in it is a real answer that looks exactly like a folder
 * nobody has opened, so a walk that asked the rows would re-read every empty folder in
 * the archive on every run. A cursor over document identities — which are time-ordered,
 * so nothing stored mid-run is skipped — costs one empty page per run once it has
 * caught up, and that is what this spends almost all of its life doing.
 *
 * Bounded per run for the reason every sweep here is: a backfill is hundreds of
 * thousands of pages, and a run that read them all would hold the lock for hours.
 */
@Component
class ArchivedCatalogSweep(
    private val documents: DocumentCatalog,
    private val catalogs: CatalogPageRecorder,
    private val walks: ArchiveWalkRepository,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.legislative.catalog-sweep-interval:PT1H}", initialDelay = 240_000)
    @SchedulerLock(name = "legislative-catalog-sweep")
    fun readWhatWasFiledBeforeAnybodyWasListening() {
        var after = walks.resume(WALK)
        var read = 0

        while (read < PAGES_PER_RUN) {
            val page = documents.versionsOfKind(CATALOG, after, PAGE)
            if (page.isEmpty()) break

            read += page.count { catalogs.recordWhatIsFiledIn(it.externalId, it.contentHash) }
            after = page.last().documentId
        }

        if (read == 0) return

        // Written once the run is over rather than after each page. A run that dies
        // half way is repeated, and repeating a walk that only upserts costs some
        // parsing and changes nothing — where a cursor moved ahead of the work would
        // step over pages nothing had read.
        walks.recordProgress(WALK, after)
        meters.counter("legislative.catalog.pages_read").increment(read.toDouble())
        log.info("Read {} archived catalog pages, up to {}", read, after)
    }

    private companion object {
        val CATALOG = CatalogPageRecorder.CATALOG

        /** Named for what it walks, because a second walk over another kind is a row beside it. */
        val WALK = CATALOG.value

        const val PAGE = 200

        /**
         * How many pages are fetched and parsed in one run. Eight stages a draft and
         * their folders beneath, over an archive of twenty-four thousand drafts, is a
         * backlog no single run should try to finish.
         */
        const val PAGES_PER_RUN = 500
    }
}
