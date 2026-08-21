package pl.barometr.connectors.isap

import org.slf4j.LoggerFactory
import pl.barometr.connectors.support.CanonicalJsonPayload
import pl.barometr.ingestion.api.AuditableConnector
import pl.barometr.ingestion.api.BackfillConnector
import pl.barometr.ingestion.api.BackfillPartition
import pl.barometr.ingestion.api.Cursor
import pl.barometr.ingestion.api.DeclaredVolume
import pl.barometr.ingestion.api.FetchResult
import pl.barometr.ingestion.api.IncrementalConnector
import pl.barometr.ingestion.api.PayloadKind
import pl.barometr.ingestion.api.RawDocumentSink
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.ingestion.api.SourceAccessDeniedException
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.IngestionMode
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * ISAP — everything that was actually enacted, through the ELI API.
 *
 * This is the source that closes the path the other two open. Sejm shows a bill
 * being voted and RPL shows it being drafted; only here does it become an act with
 * a date it entered into force, a list of what it changed, and — the field the
 * whole identity story hangs on — the number of the Sejm print it came from.
 *
 * What to read, in what order, lives here; how the API speaks lives in
 * [IsapApiClient], how an act is addressed in [IsapExternalIds], and how it is
 * rendered for hashing in [CanonicalJsonPayload].
 *
 * Two decisions worth stating outright.
 *
 * **One document per act, and no second request for it.** A listing item already
 * carries the act's complete metadata, so a page of a hundred acts is a hundred
 * archived documents and one request. The alternative — archiving the page — would
 * mean one corrected act re-storing its two thousand neighbours and pushing them all
 * back through the pipeline.
 *
 * **A refused journal is a gap while polling and a failure while replaying.** Both
 * follow from what a partition means: an incremental pass that loses Monitor Polski
 * still archives Dziennik Ustaw and tries again in an hour, whereas a backfill
 * partition that swallowed a refusal would mark a year of a journal complete and
 * never come back to it.
 */
class IsapConnector(
    private val api: IsapApiClient,
    private val payloads: CanonicalJsonPayload,
    private val clock: Clock,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
    private val incrementalLookback: Duration = DEFAULT_INCREMENTAL_LOOKBACK,
) : IncrementalConnector, BackfillConnector, AuditableConnector {

    private val log = LoggerFactory.getLogger(javaClass)

    override val id = ID

    // ——— Incremental ————————————————————————————————————————————————————————

    /**
     * Reads what each journal published since the last pass, less a lookback window.
     *
     * The window overlaps deliberately: publication and indexing are not the same
     * instant, and an act can appear in the listing days after the date it carries.
     * Re-reading is free — the sink recognises content it already holds — whereas a
     * cursor advanced to the last seen day would lose whatever landed behind it.
     */
    override fun readChangesSince(cursor: Cursor?, sink: RawDocumentSink): FetchResult {
        val publishers = api.publishers()
        if (publishers.isEmpty()) return reportMissingPublishers(sink)

        val today = LocalDate.now(clock)
        val publishedThrough = cursor?.get(CURSOR_PUBLISHED_THROUGH)?.let(LocalDate::parse) ?: today
        val since = publishedThrough.minusDays(incrementalLookback.toDays())
        val knownChange = cursor?.get(CURSOR_LAST_CHANGE)?.let(LocalDateTime::parse)

        val passes = publishers.map { publisher -> readPublishedSince(publisher, since, knownChange, sink) }
        val latestChange = (passes.mapNotNull { it.latestChange } + listOfNotNull(knownChange)).maxOrNull()

        return FetchResult(
            nextCursor = Cursor(
                IngestionMode.INCREMENTAL,
                buildMap {
                    // Held back when a journal was refused, so the window keeps
                    // stretching back until a pass reads every journal cleanly.
                    put(
                        CURSOR_PUBLISHED_THROUGH,
                        if (passes.all { it.completed }) today.toString() else publishedThrough.toString(),
                    )
                    latestChange?.let { put(CURSOR_LAST_CHANGE, it.toString()) }
                },
            ),
            // A journal we were refused said nothing about whether it changed, so
            // only a pass that read every journal may claim the source is quiet.
            // Collapsing the two would turn an outage into a healthy idle poll —
            // the one failure this system is least able to notice.
            sourceUnchanged = passes.all { it.completed } && passes.none { it.changed },
        )
    }

    private class PublisherPass(
        val changed: Boolean,
        val latestChange: LocalDateTime?,
        val completed: Boolean = true,
    )

    /**
     * The first page doubles as the check that decides whether the rest is worth
     * fetching: it is the newest end of the window, so a journal where nothing has
     * been touched since the last pass costs exactly one request. Without it, a
     * quarter-hourly poll of two journals would download the whole window every time
     * to discover that nothing moved.
     */
    private fun readPublishedSince(
        publisher: IsapPublisher,
        since: LocalDate,
        knownChange: LocalDateTime?,
        sink: RawDocumentSink,
    ): PublisherPass = try {
        val first = api.actsPublishedSince(publisher.code, since, offset = 0, limit = pageSize)
        recordWarnings(first, sink)

        val newest = first.acts.mapNotNull { it.changedAt }.maxOrNull()
        if (knownChange != null && (newest == null || !newest.isAfter(knownChange))) {
            log.debug("{} unchanged since {}", publisher.code, knownChange)
            PublisherPass(changed = false, latestChange = newest)
        } else {
            PublisherPass(changed = true, latestChange = archiveWindow(publisher, since, first, sink))
        }
    } catch (denied: SourceAccessDeniedException) {
        log.warn("Denied access to {}: {}", denied.resource, denied.reason)
        sink.recordSchemaWarning(
            SchemaWarning(denied.resource, SchemaWarning.Kind.ACCESS_DENIED, denied.reason),
        )
        PublisherPass(changed = false, latestChange = null, completed = false)
    }

    /** Archives the whole window, starting from the page already fetched. */
    private fun archiveWindow(
        publisher: IsapPublisher,
        since: LocalDate,
        first: IsapPage,
        sink: RawDocumentSink,
    ): LocalDateTime? {
        var page = first
        var offset = 0
        var latestChange: LocalDateTime? = null

        while (true) {
            archive(page, sink)
            latestChange = maxOfNullable(latestChange, page.acts.mapNotNull { it.changedAt }.maxOrNull())

            offset += page.itemsServed
            // A page that served nothing ends the walk whatever the total says: the
            // listing has shrunk under us, and asking again for the same offset is
            // how a poll turns into a loop.
            if (page.itemsServed == 0 || offset >= page.totalCount) return latestChange

            page = api.actsPublishedSince(publisher.code, since, offset, pageSize)
            recordWarnings(page, sink)
        }
    }

    // ——— Backfill ———————————————————————————————————————————————————————————

    /**
     * One partition per journal-year, because that is the pair every search is
     * scoped by and the pair the API states a total for.
     *
     * The years come from the API rather than from the requested window: Dziennik
     * Ustaw has nothing for 1940 and Monitor Polski nothing before 1930, and
     * partitions for years that do not exist would be replays that can never finish.
     * Newest first, so an interrupted replay already holds the years anyone asks
     * about.
     */
    override fun partitions(from: LocalDate, to: LocalDate): List<BackfillPartition> =
        api.publishers()
            .flatMap { publisher -> publisher.years.map { year -> publisher to year } }
            .filter { (_, year) -> year in from.year..to.year }
            .sortedWith(compareByDescending<Pair<IsapPublisher, Int>> { it.second }.thenBy { it.first.code })
            .map { (publisher, year) ->
                BackfillPartition(
                    key = IsapPartitionKey(publisher.code, year).toString(),
                    label = "${publisher.name} $year",
                )
            }

    /**
     * Reads one page, not the whole year.
     *
     * The cursor becomes durable only when this returns, so a year read in one call
     * would discard every page of it on an interruption — which is the failure
     * backfill exists to prevent.
     */
    override fun readPartitionChunk(
        partition: BackfillPartition,
        cursor: Cursor?,
        sink: RawDocumentSink,
    ): FetchResult {
        val key = IsapPartitionKey.parse(partition.key)
        val offset = cursor?.get(CURSOR_OFFSET)?.toIntOrNull() ?: 0

        val page = api.actsInYear(key.publisher, key.year, offset, pageSize)
        recordWarnings(page, sink)
        archive(page, sink)

        val nextOffset = offset + page.itemsServed
        val exhausted = page.itemsServed == 0 || nextOffset >= page.totalCount

        return FetchResult(
            nextCursor = Cursor(
                IngestionMode.BACKFILL,
                buildMap {
                    put(CURSOR_OFFSET, nextOffset.toString())
                    if (exhausted) put(Cursor.PARTITION_DONE, "true")
                },
            ),
            exhausted = exhausted,
        )
    }

    // ——— Completeness ————————————————————————————————————————————————————————

    /**
     * What the API says a journal-year holds.
     *
     * `totalCount` is stated for the whole filter and does not depend on the page
     * requested, so comparing it against the archive detects the failure the audit
     * exists for: a replay that stopped early and left a year half-read. It is not
     * evidence about the index itself — if ISAP omitted an act from its own search,
     * the count would omit it too — and the only figure that would settle that is
     * the publisher-wide `actsCount`, which belongs to no single partition.
     */
    override fun declaredVolumes(partition: BackfillPartition): List<DeclaredVolume> {
        val key = IsapPartitionKey.parse(partition.key)
        val declared = api.actsInYear(key.publisher, key.year, offset = 0, limit = 1)

        return listOf(
            DeclaredVolume(
                partition = partition.key,
                kind = "act",
                externalIdPrefix = IsapExternalIds.yearPrefix(key.publisher, key.year),
                declaredCount = declared.totalCount,
                isAuthoritative = true,
            ),
        )
    }

    // ——— Writing ————————————————————————————————————————————————————————————

    private fun archive(page: IsapPage, sink: RawDocumentSink) {
        page.acts.forEach { act ->
            sink.archive(
                RawPayload(
                    externalId = IsapExternalIds.act(act.eli),
                    payload = payloads.bytesOf(act.body),
                    kind = PayloadKind.JSON,
                ),
            )
        }
    }

    private fun recordWarnings(page: IsapPage, sink: RawDocumentSink) {
        page.warnings.forEach(sink::recordSchemaWarning)
    }

    private fun reportMissingPublishers(sink: RawDocumentSink): FetchResult {
        sink.recordSchemaWarning(
            SchemaWarning("/acts", SchemaWarning.Kind.MISSING_FIELD, "no publishers returned"),
        )
        return FetchResult.NOTHING
    }

    private fun maxOfNullable(left: LocalDateTime?, right: LocalDateTime?): LocalDateTime? =
        listOfNotNull(left, right).maxOrNull()

    companion object {
        val ID = ConnectorId("isap")

        /**
         * Acts per request. A listing item carries an act's full metadata, so a
         * hundred of them are roughly a hundred kilobytes: few enough requests that a
         * year costs a handful, small enough that an interrupted chunk loses little.
         */
        const val DEFAULT_PAGE_SIZE = 100

        /**
         * How far behind the last pass an incremental window starts. Two weeks
         * because ISAP indexes an act days after the date it carries, and because
         * re-reading costs nothing at a sink that recognises content it already has.
         */
        val DEFAULT_INCREMENTAL_LOOKBACK: Duration = Duration.ofDays(14)

        const val CURSOR_PUBLISHED_THROUGH = "publishedThrough"
        const val CURSOR_LAST_CHANGE = "lastChange"
        const val CURSOR_OFFSET = "offset"
    }
}
