package pl.barometr.connectors.sejm

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
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.IngestionMode
import java.time.LocalDate

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
) : IncrementalConnector, BackfillConnector, AuditableConnector {

    private val log = LoggerFactory.getLogger(javaClass)

    override val id = ID

    // ——— Incremental ————————————————————————————————————————————————————————

    override fun readChangesSince(cursor: Cursor?, sink: RawDocumentSink): FetchResult {
        val term = api.currentTerm() ?: return reportMissingTerms(sink)

        if (isUnchangedSince(term, cursor)) {
            log.debug("Term {} unchanged since {}", term.number, term.printsLastChangedAt)
            return FetchResult(nextCursor = cursor, sourceUnchanged = true)
        }

        readTermRegisters(term.number, sink)
        // The current term is read whole: it is one term rather than a decade of
        // them, and splitting it would only delay today's documents.
        readProceedings(term.number, sink, after = null, limit = null)

        return FetchResult(
            // Advanced only after a completed pass; moving it earlier would skip
            // documents permanently when a run dies halfway.
            nextCursor = Cursor(
                IngestionMode.INCREMENTAL,
                buildMap {
                    put(CURSOR_TERM, term.number.toString())
                    term.printsLastChangedAt?.let { put(CURSOR_PRINTS_LAST_CHANGED, it) }
                },
            ),
        )
    }

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

        return FetchResult(
            nextCursor = Cursor(
                IngestionMode.BACKFILL,
                buildMap {
                    put(CURSOR_TERM, term.toString())
                    put(CURSOR_REGISTERS_DONE, "true")
                    chunk.lastProceeding?.let { put(CURSOR_LAST_PROCEEDING, it.toString()) }
                    if (chunk.isComplete) put(Cursor.PARTITION_DONE, "true")
                },
            ),
            exhausted = chunk.isComplete,
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
        )
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
     * Sittings after [after], at most [limit] of them, each followed by its votings.
     * Reports how far it got and whether the term is finished.
     */
    private fun readProceedings(
        term: Int,
        sink: RawDocumentSink,
        after: Int?,
        limit: Int?,
    ): ProceedingChunk {
        val pending = api.proceedings(term)
            .filter { after == null || it.number > after }
            .sortedBy { it.number }

        val batch = if (limit != null) pending.take(limit) else pending
        var lastProceeding = after

        batch.forEach { proceeding ->
            store(SejmExternalIds.proceeding(term, proceeding.number), proceeding.entity, sink)
            readVotings(term, proceeding.number, sink)
            lastProceeding = proceeding.number
        }

        return ProceedingChunk(lastProceeding, isComplete = batch.size >= pending.size)
    }

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
                payload = payloads.bytesOf(entity),
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

        const val CURSOR_TERM = "term"
        const val CURSOR_PRINTS_LAST_CHANGED = "prints.lastChanged"
        const val CURSOR_REGISTERS_DONE = "registersDone"
        const val CURSOR_LAST_PROCEEDING = "lastProceeding"
    }
}
