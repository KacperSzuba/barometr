package pl.barometr.connectors.rcl

import org.slf4j.LoggerFactory
import pl.barometr.ingestion.api.AuditableConnector
import pl.barometr.ingestion.api.BackfillConnector
import pl.barometr.ingestion.api.BackfillPartition
import pl.barometr.ingestion.api.Cursor
import pl.barometr.ingestion.api.DeclaredVolume
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.ingestion.api.FetchResult
import pl.barometr.ingestion.api.IncrementalConnector
import pl.barometr.ingestion.api.PayloadKind
import pl.barometr.ingestion.api.RawDocumentSink
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.api.SourceAccessDeniedException
import pl.barometr.ingestion.api.SourceFetchException
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.IngestionMode
import java.net.URI
import java.time.LocalDate

/**
 * Rządowy Proces Legislacyjny — where a draft lives before the Sejm ever sees it.
 *
 * This class decides *what* to read and in what order. How pages are fetched lives
 * in [RclSiteClient], how they are read in the three parsers, how a page is
 * addressed in [RclExternalIds] — so what follows should read as a description of a
 * walk rather than as a scraper.
 *
 * The walk is the same shape in both modes — index, then card, then the register
 * and the stage catalogs behind it — but the two modes order their index
 * differently, and that is the one decision here worth stating plainly. Backfill
 * reads oldest first, which makes paging stable: RPL appends new drafts at the end,
 * so page 40 holds the same drafts tomorrow and a replay can resume mid-collection.
 * Incremental reads most-recently-changed first, so it can stop the moment it
 * reaches drafts it already has. Neither ordering would do the other's job.
 */
class RclConnector(
    private val site: RclSiteClient,
    private val pages: RclPages,
    private val listings: RclListingParser,
    private val cards: RclProjectCardParser,
    private val registers: RclChangeRegisterParser,
    /** How far a single call walks before its cursor becomes durable. */
    val settings: RclWalkSettings = RclWalkSettings(),
) : IncrementalConnector, BackfillConnector, AuditableConnector {

    private val log = LoggerFactory.getLogger(javaClass)

    override val id = ID

    // ——— Incremental ————————————————————————————————————————————————————————

    override fun fetch(cursor: Cursor?, sink: RawDocumentSink): FetchResult {
        val changedSince = cursor?.get(CURSOR_CHANGED_SINCE)?.let(LocalDate::parse)
        var newest = changedSince
        var draftsVisited = 0

        RclProjectType.entries.forEach { type ->
            val scan = readRecentlyChanged(type, changedSince, sink)
            draftsVisited += scan.draftsVisited
            val latest = scan.newestChange
            if (latest != null && (newest == null || latest.isAfter(newest))) newest = latest
        }

        return FetchResult(
            // Advanced only once every kind has been walked. Moving it earlier
            // would permanently skip whatever changed in the kinds not yet reached
            // when a run dies halfway.
            nextCursor = Cursor(
                IngestionMode.INCREMENTAL,
                buildMap { newest?.let { put(CURSOR_CHANGED_SINCE, it.toString()) } },
            ),
            // Counted in drafts reached, not documents stored. Government drafting
            // is quiet for days, so a pass that finds nothing to visit is healthy —
            // but a pass that visited drafts and stored none of them is a different
            // event, and collapsing the two would hide it from the anomaly check.
            sourceUnchanged = draftsVisited == 0,
        )
    }

    /**
     * Walks one kind's index newest-change-first, stopping at the first page that
     * is entirely older than [changedSince].
     *
     * The comparison is inclusive because RPL prints modification dates to the day:
     * a draft touched later the same day would otherwise be missed forever. The
     * cost of re-reading a day's drafts on each pass is a handful of requests and
     * nothing at all in storage, since the sink recognises unchanged content.
     *
     * With no cursor there is no frontier to stop at, so the walk takes the first
     * page and no more. That is the whole point of the mode: an incremental run
     * establishes where "now" is, and reading history is backfill's job. Without
     * the limit the first poll after a deployment would crawl all twenty-four
     * thousand drafts at one request per five seconds — a fortnight of traffic
     * arriving because a cursor happened to be empty.
     */
    private fun readRecentlyChanged(
        type: RclProjectType,
        changedSince: LocalDate?,
        sink: RawDocumentSink,
    ): RecentScan {
        var newest: LocalDate? = null
        var visited = 0
        var pageNumber = 1

        while (true) {
            // Index pages are read but never archived: their content changes
            // whenever anything anywhere is filed, so storing them would churn the
            // archive daily while adding nothing a card does not already say.
            val indexPage = readIndexPage(pages.recentlyChangedListing(type, pageNumber, settings.pageSize))
            val listing = listings.readListing(indexPage.document)
            if (listing.isEmpty) return RecentScan(newest, visited)

            listing.entries.mapNotNull { it.modifiedAt }.maxOrNull()?.let { latest ->
                if (newest == null || latest.isAfter(newest)) newest = latest
            }

            val changed = listing.entries.filter { it.changedOnOrAfter(changedSince) }
            changed.forEach { visitProject(type, it.projectId, changedSince, sink) }
            visited += changed.size

            if (changedSince == null) return RecentScan(newest, visited)

            // A page holding anything already known means the newest-first ordering
            // has carried us past the frontier; everything beyond is older still.
            if (changed.size < listing.entries.size) return RecentScan(newest, visited)
            if (pageNumber >= listing.pageCount(settings.pageSize)) return RecentScan(newest, visited)
            pageNumber++
        }
    }

    private fun RclListingEntry.changedOnOrAfter(threshold: LocalDate?): Boolean {
        if (threshold == null) return true
        // A row without a date is visited rather than skipped: an unreadable date
        // should cost one wasted request, not a permanently invisible draft.
        val modified = modifiedAt ?: return true
        return !modified.isBefore(threshold)
    }

    // ——— Backfill ———————————————————————————————————————————————————————————

    /**
     * One partition per kind of draft, and the window is deliberately ignored.
     *
     * RPL does expose a `createDateFrom` filter, but its accepted format is not
     * something this connector can verify without asking the live site, and a date
     * filter that silently matches nothing would produce an empty archive reported
     * as a clean run. Reading each kind whole is slower and cannot be wrong.
     */
    override fun partitions(from: LocalDate, to: LocalDate): List<BackfillPartition> =
        RclProjectType.entries.map { BackfillPartition(it.slug, it.label) }

    override fun fetchPartition(
        partition: BackfillPartition,
        cursor: Cursor?,
        sink: RawDocumentSink,
    ): FetchResult {
        val type = RclProjectType.ofSlug(partition.key)
            ?: error("Unknown RCL partition '${partition.key}'")

        val firstPage = (cursor?.get(CURSOR_LAST_PAGE)?.toIntOrNull() ?: 0) + 1
        var lastCompletedPage = firstPage - 1
        var pageCount = 0

        for (offset in 0 until settings.pagesPerChunk) {
            val pageNumber = firstPage + offset
            val indexPage = readIndexPage(pages.oldestFirstListing(type, pageNumber, settings.pageSize))
            val listing = listings.readListing(indexPage.document)
            pageCount = listing.pageCount(settings.pageSize)
            if (listing.isEmpty) break

            listing.entries.forEach { visitProject(type, it.projectId, since = null, sink) }
            lastCompletedPage = pageNumber
            if (pageNumber >= pageCount) break
        }

        val isComplete = pageCount > 0 && lastCompletedPage >= pageCount
        return FetchResult(
            nextCursor = Cursor(
                IngestionMode.BACKFILL,
                buildMap {
                    put(CURSOR_LAST_PAGE, lastCompletedPage.toString())
                    if (isComplete) put(Cursor.PARTITION_DONE, "true")
                },
            ),
            exhausted = isComplete,
        )
    }

    // ——— Completeness ————————————————————————————————————————————————————————

    /**
     * What RPL says one kind holds: "Lista projektów według wybranych kryteriów:
     * 2602", printed by the server from its own count.
     *
     * Authoritative, and for the same reason the Sejm's print count is: it is a
     * figure the source states rather than one we arrived at by counting what it
     * handed us, so comparing it against the archive genuinely detects a replay
     * that lost pages. Fetched with a single-row page, which makes the probe as
     * cheap as the header it is after.
     */
    override fun declaredVolumes(partition: BackfillPartition): List<DeclaredVolume> {
        val type = RclProjectType.ofSlug(partition.key) ?: return emptyList()
        val probe = readIndexPage(pages.oldestFirstListing(type, page = 1, pageSize = 1))

        return listOf(
            DeclaredVolume(
                partition = partition.key,
                kind = "project",
                externalIdPrefix = RclExternalIds.projectPrefix(type),
                declaredCount = listings.readListing(probe.document).totalCount,
                isAuthoritative = true,
            ),
        )
    }

    // ——— Reading ————————————————————————————————————————————————————————————

    /**
     * One draft: its card, its change register, and the catalogs behind the stages
     * it has actually reached.
     *
     * The register is fetched every time rather than opportunistically, because it
     * is the only page on the site that timestamps a stage transition to the
     * minute — a card can only say which day a stage last moved, and a bitemporal
     * record wants better than that.
     */
    private fun visitProject(
        type: RclProjectType,
        projectId: String,
        since: LocalDate?,
        sink: RawDocumentSink,
    ) {
        val cardPage = readProjectPage(pages.project(projectId), sink) ?: return
        store(RclExternalIds.project(type, projectId), cardPage, sink)

        val card = cards.readProjectCard(cardPage.document)
        if (card == null) {
            sink.warn(
                SchemaWarning(
                    "/projekt/$projectId",
                    SchemaWarning.Kind.MISSING_FIELD,
                    "no project id could be recovered from the card",
                ),
            )
            return
        }

        readProjectPage(pages.projectChangeRegister(projectId), sink)?.let { page ->
            store(RclExternalIds.projectChangeRegister(type, projectId), page, sink)
        }

        val visited = mutableSetOf<String>()
        card.visitableStages
            .filter { it.touchedOnOrAfter(since) }
            .forEach { stage ->
                visitCatalog(type, projectId, stage.catalogId, settings.catalogDepth, visited, sink)
            }
    }

    /**
     * One catalog: the page itself, its event log, and whatever catalogs the log
     * says are filed beneath it.
     *
     * The tree is discovered from the register rather than from the page, because
     * the register is the part we can read: it names each child catalog and links
     * it, while the catalog page's own markup has never been captured. The page is
     * still fetched and archived whole, so the day its structure is known the
     * documents can be extracted from the archive instead of re-crawled.
     *
     * [visited] guards against a register that names an ancestor. RPL has no reason
     * to produce one, but recursion driven by scraped links should not be able to
     * spin forever on a page nobody has seen.
     */
    private fun visitCatalog(
        type: RclProjectType,
        projectId: String,
        catalogId: String,
        remainingDepth: Int,
        visited: MutableSet<String>,
        sink: RawDocumentSink,
    ) {
        if (remainingDepth <= 0 || !visited.add(catalogId)) return

        readProjectPage(pages.catalog(projectId, catalogId), sink)?.let { page ->
            store(RclExternalIds.catalog(type, projectId, catalogId), page, sink)
        }

        val registerPage = readProjectPage(pages.catalogChangeRegister(catalogId), sink) ?: return
        store(RclExternalIds.catalogChangeRegister(type, projectId, catalogId), registerPage, sink)

        if (remainingDepth <= 1) return
        registers.readChangeRegister(registerPage.document).childCatalogs.forEach { child ->
            visitCatalog(type, projectId, child.catalogId, remainingDepth - 1, visited, sink)
        }
    }

    private fun RclStage.touchedOnOrAfter(threshold: LocalDate?): Boolean {
        if (threshold == null) return true
        val touched = lastModifiedAt ?: return true
        return !touched.isBefore(threshold)
    }

    private fun store(externalId: ExternalId, page: RclPage, sink: RawDocumentSink) {
        sink.accept(
            RawPayload(
                externalId = externalId,
                payload = page.html,
                kind = PayloadKind.HTML,
                etag = page.etag,
                lastModified = page.lastModified,
            ),
        )
    }

    /**
     * Fetches an index page, tolerating nothing.
     *
     * Deliberately the opposite of [readProjectPage]. Without an index there is
     * nothing to walk, so a refusal here is not a gap in the archive — it is the
     * archive. Swallowed, it would produce the worst outcome this system can have:
     * a source blocked by robots.txt reporting a clean run with zero documents,
     * indistinguishable from a quiet weekend, for as long as nobody looked.
     */
    private fun readIndexPage(url: URI): RclPage =
        site.readPage(url) ?: throw SourceFetchException(url.path, "unexpected 304 on an index page")

    /**
     * Fetches one draft's page, turning both a refusal and a failure into a
     * recorded gap rather than a failed run.
     *
     * A single page RPL will not serve is a hole with a cause worth writing down;
     * it is not a reason to abandon the other twenty thousand. This matters most in
     * backfill, where one poison page that aborted the run would stop the replay
     * from ever getting past it.
     */
    private fun readProjectPage(url: URI, sink: RawDocumentSink): RclPage? = try {
        site.readPage(url)
    } catch (denied: SourceAccessDeniedException) {
        log.warn("Denied access to {}: {}", denied.resource, denied.reason)
        sink.warn(SchemaWarning(denied.resource, SchemaWarning.Kind.ACCESS_DENIED, denied.reason))
        null
    } catch (failed: SourceFetchException) {
        log.warn("Could not read {}: {}", failed.resource, failed.detail)
        sink.warn(SchemaWarning(failed.resource, SchemaWarning.Kind.MISSING_FIELD, failed.detail))
        null
    }

    /**
     * What one kind's index pass found.
     *
     * [draftsVisited] is counted rather than taken from the sink because it means
     * something the sink cannot see: a draft whose card RPL refused was still
     * visited, and a pass that visited drafts and stored none of them is a very
     * different event from a quiet week.
     */
    private class RecentScan(val newestChange: LocalDate?, val draftsVisited: Int)

    companion object {
        val ID = ConnectorId("rcl")

        const val CURSOR_CHANGED_SINCE = "changedSince"
        const val CURSOR_LAST_PAGE = "lastPage"
    }
}
