package pl.barometr.connectors.sejm

import org.slf4j.LoggerFactory
import pl.barometr.connectors.support.CanonicalJsonPayload
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
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.IngestionMode
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Sejm of the Republic of Poland — the project's first and most important source.
 *
 * This class decides *what* to read and in what order. How the API speaks lives in
 * [SejmApiClient], how an entity is addressed in [SejmExternalIds], and how it is
 * rendered for hashing in [CanonicalJsonPayload] — so the methods below read as a
 * description of the ingestion process rather than as a parser.
 *
 * The one decision worth stating here: entities go to the sink **one at a time**,
 * never as whole collections. The API has no pagination and no ETag, so every poll
 * downloads a complete collection; storing that as a single document would mean one
 * amended print re-stored all 3205 and pushed the whole archive back through the
 * pipeline. Per-entity granularity lets content hashing reduce that to one document.
 */
class SejmConnector(
    private val api: SejmApiClient,
    private val payloads: CanonicalJsonPayload,
    /**
     * How many proceedings one backfill call reads before committing its cursor.
     * Small enough that an interruption costs minutes rather than hours, large
     * enough that dispatcher polling is not the bottleneck.
     */
    private val proceedingsPerChunk: Int = DEFAULT_PROCEEDINGS_PER_CHUNK,
    /** Index entries per request. The processes index is the one paged endpoint here. */
    private val processIndexPageSize: Int = DEFAULT_PROCESS_INDEX_PAGE_SIZE,
    /** Processes one backfill chunk fetches in full, each of them a request. */
    private val processesPerChunk: Int = DEFAULT_PROCESSES_PER_CHUNK,
) : IncrementalConnector, BackfillConnector, AuditableConnector {

    private val log = LoggerFactory.getLogger(javaClass)

    override val id = ID

    // ——— Incremental ————————————————————————————————————————————————————————

    override fun readChangesSince(cursor: Cursor?, sink: RawDocumentSink): FetchResult {
        val term = api.currentTerm() ?: return reportMissingTerms(sink)

        // Walked on every pass, before the cheap check and regardless of what it says.
        // Nothing in the term summary reports on processes, and a bill leaving
        // committee for its second reading files no print — so `prints.lastChanged`
        // sits still while the thing a user is watching moves. The index itself is
        // cheap; only a process that moved is fetched in full.
        val processes = readChangedProcesses(term.number, sink, since = processesChangedThrough(cursor))

        val printsUnchanged = isUnchangedSince(term, cursor)
        if (printsUnchanged) {
            log.debug("Term {} prints unchanged since {}", term.number, term.printsLastChangedAt)
        } else {
            readTermRegisters(term.number, sink)
            // The current term is read whole: it is one term rather than a decade of
            // them, and splitting it would only delay today's documents.
            readProceedings(term.number, sink, after = null, limit = null)
        }

        return FetchResult(
            // Advanced only after a completed pass; moving it earlier would skip
            // documents permanently when a run dies halfway.
            nextCursor = Cursor(
                IngestionMode.INCREMENTAL,
                buildMap {
                    put(CURSOR_TERM, term.number.toString())
                    term.printsLastChangedAt?.let { put(CURSOR_PRINTS_LAST_CHANGED, it) }
                    (processes.latestChange ?: processesChangedThrough(cursor))?.let {
                        put(CURSOR_PROCESSES_CHANGED_THROUGH, it.toString())
                    }
                },
            ),
            sourceUnchanged = printsUnchanged && processes.moved == 0,
        )
    }

    private fun processesChangedThrough(cursor: Cursor?): LocalDateTime? =
        cursor?.get(CURSOR_PROCESSES_CHANGED_THROUGH)?.let(LocalDateTime::parse)

    /**
     * The one small response that decides whether the large ones are worth
     * fetching, and the only reason a fifteen-minute cycle is affordable here.
     */
    private fun isUnchangedSince(term: SejmTerm, cursor: Cursor?): Boolean {
        val lastChanged = term.printsLastChangedAt ?: return false
        return lastChanged == cursor?.get(CURSOR_PRINTS_LAST_CHANGED)
    }

    // ——— Backfill ———————————————————————————————————————————————————————————

    /**
     * One partition per parliamentary term, because that is how the API is
     * organised: every path is scoped to a term, so a term is the natural unit that
     * can be read start to finish independently of the others.
     *
     * Newest first, so a replay that is interrupted — or deliberately stopped short
     * — already holds the years anyone will actually ask about.
     */
    override fun partitions(from: LocalDate, to: LocalDate): List<BackfillPartition> =
        api.terms()
            .filter { it.overlaps(from, to) }
            .sortedByDescending { it.number }
            .map { BackfillPartition(SejmPartitions.of(it.number), SejmPartitions.label(it)) }

    /**
     * Reads a bounded chunk, not the whole partition.
     *
     * The cursor only becomes durable once this returns, so reading a whole term in
     * one call would mean an interruption anywhere in those hours discarded all of
     * it — precisely the failure backfill exists to prevent.
     */
    override fun readPartitionChunk(
        partition: BackfillPartition,
        cursor: Cursor?,
        sink: RawDocumentSink,
    ): FetchResult {
        val term = SejmPartitions.termOf(partition.key)

        // Registers arrive whole, so there is no mid-collection resume: the cursor
        // records that they are done rather than where they got to.
        if (cursor?.get(CURSOR_REGISTERS_DONE) != "true") {
            readTermRegisters(term, sink)
        }

        val chunk = readProceedings(
            term = term,
            sink = sink,
            after = cursor?.get(CURSOR_LAST_PROCEEDING)?.toIntOrNull(),
            limit = proceedingsPerChunk,
        )

        // Processes resume on an index offset rather than on a number: the index is
        // ordered by the term's own reckoning, and a process number is not a position
        // in it.
        val processes = readProcessChunk(
            term = term,
            sink = sink,
            offset = cursor?.get(CURSOR_PROCESS_OFFSET)?.toIntOrNull() ?: 0,
            limit = processesPerChunk,
        )
        val finished = chunk.isComplete && processes.isComplete

        return FetchResult(
            nextCursor = Cursor(
                IngestionMode.BACKFILL,
                buildMap {
                    put(CURSOR_TERM, term.toString())
                    put(CURSOR_REGISTERS_DONE, "true")
                    chunk.lastProceeding?.let { put(CURSOR_LAST_PROCEEDING, it.toString()) }
                    put(CURSOR_PROCESS_OFFSET, processes.nextOffset.toString())
                    if (finished) put(Cursor.PARTITION_DONE, "true")
                },
            ),
            exhausted = finished,
        )
    }

    // ——— Completeness ————————————————————————————————————————————————————————

    /**
     * What the Sejm API says a term holds.
     *
     * Prints are the one count worth trusting: `/sejm/term` states it independently
     * of the print list itself, so comparing it against the archive genuinely
     * detects a replay that lost records. Proceedings are counted from the very list
     * the backfill walks, which can only prove that the walk finished — flagged
     * non-authoritative so the report cannot pass that off as proof.
     *
     * Votings are absent on purpose: the API publishes no total for them, and a
     * number we made up would be worse than none.
     */
    override fun declaredVolumes(partition: BackfillPartition): List<DeclaredVolume> {
        val term = SejmPartitions.termOf(partition.key)
        val declared = api.terms().firstOrNull { it.number == term } ?: return emptyList()

        return listOf(
            DeclaredVolume(
                partition = partition.key,
                kind = "print",
                externalIdPrefix = SejmExternalIds.printPrefix(term),
                declaredCount = declared.printCount,
                isAuthoritative = true,
            ),
            DeclaredVolume(
                partition = partition.key,
                kind = "proceeding",
                externalIdPrefix = SejmExternalIds.proceedingPrefix(term),
                declaredCount = api.proceedings(term).size,
                isAuthoritative = false,
            ),
            DeclaredVolume(
                partition = partition.key,
                kind = "process",
                externalIdPrefix = SejmExternalIds.processPrefix(term),
                declaredCount = countProcesses(term),
                // Counted from the very index the backfill walks, so it proves the walk
                // finished and nothing more. The API publishes no independent total for
                // processes, and inventing one would make the report reassuring rather
                // than true.
                isAuthoritative = false,
            ),
        )
    }

    private fun countProcesses(term: Int): Int {
        var offset = 0

        while (true) {
            val page = api.processes(term, offset, processIndexPageSize)
            offset += page.size
            if (page.size < processIndexPageSize) return offset
        }
    }

    // ——— Reading ————————————————————————————————————————————————————————————

    /** Prints, clubs and members: the term's standing registers. */
    private fun readTermRegisters(term: Int, sink: RawDocumentSink) {
        collectDenials(sink) {
            api.prints(term).forEach { store(SejmExternalIds.print(term, it.naturalKey), it, sink) }
        }
        collectDenials(sink) {
            api.clubs(term).forEach { store(SejmExternalIds.club(term, it.naturalKey), it, sink) }
        }
        collectDenials(sink) {
            api.members(term).forEach { store(SejmExternalIds.member(term, it.naturalKey), it, sink) }
        }
    }

    private class ProceedingChunk(val lastProceeding: Int?, val isComplete: Boolean)

    /**
     * Numbered sittings after [after], at most [limit] of them, each followed by its
     * votings — and every unnumbered sitting, which pagination cannot carry.
     * Reports how far it got and whether the term is finished.
     */
    private fun readProceedings(
        term: Int,
        sink: RawDocumentSink,
        after: Int?,
        limit: Int?,
    ): ProceedingChunk {
        val sittings = api.proceedings(term)
        readUnnumberedSittings(term, sittings, sink)

        val pending = sittings
            .mapNotNull { sitting -> sitting.number?.let { number -> number to sitting } }
            .filter { (number, _) -> after == null || number > after }
            .sortedBy { (number, _) -> number }

        val batch = if (limit != null) pending.take(limit) else pending
        var lastProceeding = after

        batch.forEach { (number, sitting) ->
            store(SejmExternalIds.proceeding(term, number), sitting.entity, sink)
            readVotings(term, number, sink)
            lastProceeding = number
        }

        return ProceedingChunk(lastProceeding, isComplete = batch.size >= pending.size)
    }

    /**
     * Sittings the API has not numbered, read whole on every pass and deliberately
     * never paginated.
     *
     * They cannot be. The cursor resumes on a sitting number and these have none, so
     * a chunk ending inside the group would leave the rest of it behind for good —
     * which is exactly what happened while all eleven of them shared the number zero.
     * Re-reading them costs nothing: they arrive inside the list already fetched, they
     * have no votings to look up, and content addressing makes a repeat a no-op at the
     * sink. That is what separates them from the term's registers, which are thousands
     * of documents and are read once per partition.
     */
    private fun readUnnumberedSittings(
        term: Int,
        sittings: List<SejmProceeding>,
        sink: RawDocumentSink,
    ) {
        sittings.filter { it.number == null }.forEach { sitting ->
            val firstDate = sitting.firstDate
            if (firstDate == null) {
                // Neither a number nor a date leaves nothing to address it by, and an
                // address invented here would collide with the next such sitting.
                sink.recordSchemaWarning(
                    SchemaWarning(
                        "/sejm/term$term/proceedings[].dates",
                        SchemaWarning.Kind.MISSING_FIELD,
                        "a sitting with neither a number nor a date cannot be addressed",
                    ),
                )
                return@forEach
            }

            store(SejmExternalIds.proceedingOn(term, firstDate), sitting.entity, sink)
        }
    }

    private class ProcessPass(val moved: Int, val latestChange: LocalDateTime?)

    private class ProcessChunk(val nextOffset: Int, val isComplete: Boolean)

    /**
     * Every index page, and in full only the processes that moved.
     *
     * The two halves are what makes a quarter-hourly poll affordable on a collection
     * of well over a thousand: the index costs three requests whatever happens, and a
     * process is fetched only when its own stamp is newer than the last pass. A
     * process whose stamp cannot be read is fetched — the cheap check failing should
     * cost a request, never a document.
     */
    private fun readChangedProcesses(term: Int, sink: RawDocumentSink, since: LocalDateTime?): ProcessPass {
        var offset = 0
        var moved = 0
        var latestChange = since

        while (true) {
            val page = api.processes(term, offset, processIndexPageSize)
            val changed = page.filter { since == null || it.changedAt == null || it.changedAt.isAfter(since) }
            changed.forEach { storeProcess(term, it, sink) }

            moved += changed.size
            latestChange = maxOfNullable(latestChange, page.mapNotNull { it.changedAt }.maxOrNull())
            offset += page.size

            // A short page is the end of the index; an empty one ends the walk whatever
            // else is true, because asking again for the same offset is how a poll
            // turns into a loop.
            if (page.size < processIndexPageSize) return ProcessPass(moved, latestChange)
        }
    }

    /** One page of the index, every process on it read in full. */
    private fun readProcessChunk(term: Int, sink: RawDocumentSink, offset: Int, limit: Int): ProcessChunk {
        val page = api.processes(term, offset, limit)
        page.forEach { storeProcess(term, it, sink) }

        return ProcessChunk(nextOffset = offset + page.size, isComplete = page.size < limit)
    }

    /**
     * A refusal on one process is a gap, like a refused voting: the run keeps going and
     * the next pass tries again, because the index that named it was readable.
     */
    private fun storeProcess(term: Int, summary: SejmProcessSummary, sink: RawDocumentSink) {
        collectDenials(sink) {
            store(SejmExternalIds.process(term, summary.number), api.process(term, summary.number), sink)
        }
    }

    private fun maxOfNullable(left: LocalDateTime?, right: LocalDateTime?): LocalDateTime? =
        listOfNotNull(left, right).maxOrNull()

    /** Votings hang off a sitting, so they cannot be listed in bulk. */
    private fun readVotings(term: Int, proceeding: Int, sink: RawDocumentSink) {
        collectDenials(sink) {
            api.votings(term, proceeding).forEach { voting ->
                store(SejmExternalIds.voting(term, proceeding, voting.naturalKey), voting, sink)
            }
        }
    }

    private fun store(externalId: ExternalId, entity: SejmEntity, sink: RawDocumentSink) {
        sink.archive(
            RawPayload(
                externalId = externalId,
                payload = payloads.bytesOf(entity.body),
                kind = PayloadKind.JSON,
            ),
        )
    }

    /**
     * A refusal on one resource is a gap, not a catastrophe: it is recorded and the
     * run continues. A refusal on the term list is deliberately *not* caught — with
     * no term there is nothing to read, and a source we are forbidden from reading
     * should look broken rather than idle.
     */
    private inline fun collectDenials(sink: RawDocumentSink, read: () -> Unit) {
        try {
            read()
        } catch (denied: SourceAccessDeniedException) {
            log.warn("Denied access to {}: {}", denied.resource, denied.reason)
            sink.recordSchemaWarning(
                SchemaWarning(denied.resource, SchemaWarning.Kind.ACCESS_DENIED, denied.reason),
            )
        }
    }

    private fun reportMissingTerms(sink: RawDocumentSink): FetchResult {
        sink.recordSchemaWarning(SchemaWarning("/sejm/term", SchemaWarning.Kind.MISSING_FIELD, "no terms returned"))
        return FetchResult.NOTHING
    }

    companion object {
        val ID = ConnectorId("sejm")
        const val DEFAULT_PROCEEDINGS_PER_CHUNK = 10

        /**
         * The index honours `limit`, and a term holds well over a thousand processes.
         * Five hundred keeps a poll of the whole index to three requests.
         */
        const val DEFAULT_PROCESS_INDEX_PAGE_SIZE = 500

        /**
         * A backfill chunk fetches this many processes in full, one request each, so
         * an interruption costs about a minute of crawling rather than an hour.
         */
        const val DEFAULT_PROCESSES_PER_CHUNK = 100

        const val CURSOR_TERM = "term"
        const val CURSOR_PRINTS_LAST_CHANGED = "prints.lastChanged"
        const val CURSOR_REGISTERS_DONE = "registersDone"
        const val CURSOR_LAST_PROCEEDING = "lastProceeding"
        const val CURSOR_PROCESSES_CHANGED_THROUGH = "processes.changedThrough"
        const val CURSOR_PROCESS_OFFSET = "processOffset"
    }
}
