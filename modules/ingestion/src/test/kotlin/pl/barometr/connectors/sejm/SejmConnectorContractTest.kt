package pl.barometr.connectors.sejm

import org.junit.jupiter.api.Test
import pl.barometr.connectors.support.CanonicalJsonPayload
import pl.barometr.http.HttpFetch
import pl.barometr.http.HttpOutcome
import pl.barometr.http.RefusalReason
import pl.barometr.http.SourceHttpClient
import pl.barometr.ingestion.api.BackfillPartition
import pl.barometr.ingestion.api.Cursor
import pl.barometr.ingestion.api.RawDocumentSink
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.api.SourceAccessDeniedException
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.ingestion.api.SinkOutcome
import pl.barometr.sources.api.IngestionMode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.net.URI
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract test against responses recorded from the live Sejm API.
 *
 * Recorded rather than mocked by hand, because the point is to notice when the
 * *source* changes shape — a handwritten stub only ever confirms what we already
 * believed. Refreshing the fixtures and watching this test fail is how a silent
 * API change becomes visible before it becomes missing data.
 */
class SejmConnectorContractTest {

    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    /** The connector now composes a client and a canonicaliser rather than raw HTTP. */
    private fun connectorOf(
        http: SourceHttpClient,
        proceedingsPerChunk: Int = SejmConnector.DEFAULT_PROCEEDINGS_PER_CHUNK,
        processesPerChunk: Int = SejmConnector.DEFAULT_PROCESSES_PER_CHUNK,
    ) = SejmConnector(
        api = SejmApiClient(http, BASE_URL, json),
        payloads = CanonicalJsonPayload(json),
        proceedingsPerChunk = proceedingsPerChunk,
        processesPerChunk = processesPerChunk,
    )

    @Test
    fun `splits collections into one document per entity`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()

        connectorOf(http).readChangesSince(cursor = null, sink = sink)

        // 5 prints + 2 clubs + 3 MPs + 3 sittings + 2 x 3 votings + 3 processes. The
        // third sitting is the National Assembly, which the API leaves unnumbered.
        assertEquals(22, sink.accepted.size)

        // Per-entity granularity is the whole design: with one document per
        // collection, a single amended print would re-store all 3205 of them.
        assertTrue(sink.externalIds.any { it == "term10/print/3005" })
        assertTrue(sink.externalIds.any { it.startsWith("term10/club/") })
        assertTrue(sink.externalIds.any { it.startsWith("term10/mp/") })
        assertTrue(sink.externalIds.contains("term10/proceeding/1"))
        assertTrue(sink.externalIds.any { it.matches(Regex("term10/proceeding/\\d+/voting/\\d+")) })
    }

    /**
     * Eleven of term 10's sittings arrive with `number: 0` — the National Assembly,
     * ceremonial assemblies, every sitting still only planned. Addressed by that zero
     * they were one document with eleven versions, and a backfill chunk that ended
     * inside the group lost the rest of it. The first day each of them sits is unique,
     * and is what a person calls it by.
     */
    @Test
    fun `a sitting the API never numbered is addressed by its first day`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()

        connectorOf(http).readChangesSince(cursor = null, sink = sink)

        assertTrue(sink.externalIds.contains("term10/proceeding/2025-08-06"))
        assertTrue(sink.externalIds.none { it == "term10/proceeding/0" })
        // Votings hang off a sitting number, so there are none to ask for — and the
        // API answers `/votings/0` with an empty list eleven times over if you do.
        assertTrue(http.requestedPaths.none { it.endsWith("/votings/0") })
    }

    /**
     * The one sitting this connector cannot archive: no number and no date leaves
     * nothing to address it by, and an address invented here would collide with the
     * next such sitting. Recorded as a gap in the run rather than guessed at.
     */
    @Test
    fun `a sitting with neither a number nor a date is recorded as a gap`() {
        val sink = RecordingSink()

        connectorOf(FixtureHttpClient(rewrite = ::stripDatesFromUnnumbered))
            .readChangesSince(cursor = null, sink = sink)

        assertTrue(sink.warnings.any { it.kind == SchemaWarning.Kind.MISSING_FIELD })
        assertEquals(21, sink.accepted.size, "everything else still arrived")
        assertTrue(sink.externalIds.none { it.startsWith("term10/proceeding/2025") })
    }

    @Test
    fun `advances the cursor to the term's prints timestamp`() {
        val result = connectorOf(FixtureHttpClient())
            .readChangesSince(cursor = null, sink = RecordingSink())

        val cursor = result.nextCursor!!
        assertEquals("10", cursor[SejmConnector.CURSOR_TERM])
        assertEquals("2026-08-17T14:38:12", cursor[SejmConnector.CURSOR_PRINTS_LAST_CHANGED])
    }

    /**
     * The behaviour that makes a fifteen-minute cycle affordable. The prints endpoint
     * has no ETag and ignores `limit`, so without this check every poll would pull
     * thousands of records to discover that nothing moved.
     *
     * A quiet term costs two requests, not one: the term summary, and the index of
     * processes. It reports on prints and on nothing else, so the index is the only
     * way to learn that a bill moved — see the test below.
     */
    @Test
    fun `a term where nothing moved costs the summary and the index`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()

        val result = connectorOf(http).readChangesSince(quietCursor(), sink)

        assertEquals(0, sink.accepted.size)
        assertTrue(result.sourceUnchanged)
        assertEquals(listOf("/sejm/term", "/sejm/term10/processes"), http.requestedPaths)
    }

    /**
     * The reason processes are walked whatever the term summary says.
     *
     * A bill leaving committee for its second reading files no print, so
     * `prints.lastChanged` does not move — and the passage of a bill is the thing a
     * user came to watch. Checking only the prints stamp would have left every stage
     * after the first unarchived until somebody happened to file a document.
     */
    @Test
    fun `a process that moved is read even when the prints have not`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()
        val cursor = Cursor(
            IngestionMode.INCREMENTAL,
            mapOf(
                SejmConnector.CURSOR_PRINTS_LAST_CHANGED to PRINTS_LAST_CHANGED,
                SejmConnector.CURSOR_PROCESSES_CHANGED_THROUGH to "2023-12-01T00:00:00",
            ),
        )

        val result = connectorOf(http).readChangesSince(cursor, sink)

        // Two of the three recorded processes carry a newer stamp; the third does not
        // and is never fetched.
        assertEquals(listOf("term10/process/31", "term10/process/27"), sink.externalIds)
        assertTrue(http.requestedPaths.none { it.endsWith("/processes/3") })
        assertFalse(result.sourceUnchanged, "a moved process is a change, whatever the prints say")
    }

    @Test
    fun `the cursor records the newest process the index has seen`() {
        val result = connectorOf(FixtureHttpClient()).readChangesSince(null, RecordingSink())

        assertEquals(
            NEWEST_PROCESS_CHANGE,
            result.nextCursor?.get(SejmConnector.CURSOR_PROCESSES_CHANGED_THROUGH),
        )
    }

    @Test
    fun `a refused process is recorded and the rest of the pass continues`() {
        val http = FixtureHttpClient(refusePathPattern = Regex("/sejm/term10/processes/31"))
        val sink = RecordingSink()

        connectorOf(http).readChangesSince(quietCursor(), RecordingSink())
        connectorOf(http).readChangesSince(null, sink)

        assertTrue(sink.warnings.any { it.kind == SchemaWarning.Kind.ACCESS_DENIED })
        assertTrue(sink.externalIds.contains("term10/process/27"))
        assertTrue(sink.externalIds.none { it == "term10/process/31" })
    }

    /**
     * Payloads are re-serialised with sorted keys, so an entity the source decides
     * to emit in a different field order still hashes to the same content address.
     * Without this, deduplication would silently depend on the source's serialiser
     * never changing.
     */
    @Test
    fun `field order in the response does not change the payload`() {
        val connector = connectorOf(FixtureHttpClient())

        val straight = RecordingSink()
        connector.readChangesSince(null, straight)

        val reordered = RecordingSink()
        connectorOf(FixtureHttpClient(rewrite = ::reverseKeys)).readChangesSince(null, reordered)

        val byId = { sink: RecordingSink -> sink.accepted.associate { it.externalId.value to String(it.payload) } }
        assertEquals(byId(straight), byId(reordered))
    }

    /**
     * A refusal on a single sub-resource is a gap, not a catastrophe: it is recorded
     * and the rest of the run continues.
     */
    @Test
    fun `a refused sub-resource is recorded and skipped`() {
        val sink = RecordingSink()
        val http = FixtureHttpClient(refusePathPattern = Regex("/sejm/term10/votings/\\d+"))

        val result = connectorOf(http).readChangesSince(null, sink)

        assertTrue(sink.warnings.any { it.kind == SchemaWarning.Kind.ACCESS_DENIED })
        // Prints, clubs, MPs, sittings and processes still arrived; only votings are
        // missing.
        assertEquals(16, sink.accepted.size)
        assertTrue(sink.externalIds.none { it.contains("/voting/") })
    }

    /**
     * A refusal on the term list is different in kind — there is no term to read, so
     * the run must fail visibly instead of reporting a quiet zero that looks like an
     * idle source.
     */
    @Test
    fun `a refused term list fails the run`() {
        val http = FixtureHttpClient(refusePathPattern = Regex("/sejm/term"))

        val failure = assertFailsWith<SourceAccessDeniedException> {
            connectorOf(http).readChangesSince(null, RecordingSink())
        }
        // The resource, not the sentence: a message is prose and may be reworded,
        // while what the caller has to act on is which resource was refused.
        assertEquals("/sejm/term", failure.resource)
    }

    // ——— Backfill —————————————————————————————————————————————————————————————

    /**
     * Terms are the partition unit because the API is organised that way, and the
     * order is newest-first so a replay that gets interrupted already holds the
     * years anyone will actually ask about.
     */
    @Test
    fun `partitions cover overlapping terms, newest first`() {
        val partitions = connectorOf(FixtureHttpClient())
            .partitions(LocalDate.parse("2021-01-01"), LocalDate.parse("2026-08-17"))

        // Terms 9 (2019-2023) and 10 (2023-) overlap that window; term 8 ended in 2019.
        assertEquals(listOf("term10", "term9"), partitions.map { it.key })
        assertTrue(partitions.first().label.contains("Kadencja 10"))
    }

    @Test
    fun `a window before the first term yields no partitions`() {
        val partitions = connectorOf(FixtureHttpClient())
            .partitions(LocalDate.parse("1980-01-01"), LocalDate.parse("1985-01-01"))

        assertTrue(partitions.isEmpty())
    }

    @Test
    fun `a fresh partition reads collections and proceedings`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()

        val result = connectorOf(http)
            .readPartitionChunk(BackfillPartition("term10", "Kadencja 10"), cursor = null, sink = sink)

        assertEquals(22, sink.accepted.size)
        assertTrue(result.exhausted)
        assertEquals("true", result.nextCursor!![SejmConnector.CURSOR_REGISTERS_DONE])
        assertEquals("2", result.nextCursor!![SejmConnector.CURSOR_LAST_PROCEEDING])
    }

    /**
     * The property backfill exists for: an interrupted partition continues instead of
     * starting over. Without it, a five-year replay would restart from scratch after
     * any restart — which is the difference between a day of crawling and a week.
     */
    @Test
    fun `an interrupted partition resumes instead of restarting`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()
        val cursor = Cursor(
            IngestionMode.BACKFILL,
            mapOf(
                SejmConnector.CURSOR_REGISTERS_DONE to "true",
                SejmConnector.CURSOR_LAST_PROCEEDING to "1",
            ),
        )

        connectorOf(http)
            .readPartitionChunk(BackfillPartition("term10", "Kadencja 10"), cursor, sink)

        // Collections already done, so they are not fetched again.
        assertTrue(http.requestedPaths.none { it.endsWith("/prints") })
        assertTrue(http.requestedPaths.none { it.endsWith("/clubs") })
        assertTrue(http.requestedPaths.none { it.endsWith("/MP") })

        // Proceeding 1 was already processed; only 2 and its votings are read.
        assertTrue(sink.externalIds.none { it == "term10/proceeding/1" })
        assertTrue(sink.externalIds.contains("term10/proceeding/2"))
        // The unnumbered sitting comes back with every chunk, because no cursor of
        // numbers can say whether it has been read. Re-storing it is a no-op at the
        // sink; losing it would not be.
        assertTrue(sink.externalIds.contains("term10/proceeding/2025-08-06"))
    }

    /**
     * The reason a partition is read in chunks at all: the cursor only becomes
     * durable when the call returns, so reading a whole term in one go would mean an
     * interruption anywhere in those hours discarded every bit of it.
     */
    @Test
    fun `a partition longer than one chunk reports unfinished and advances`() {
        val connector = connectorOf(FixtureHttpClient(), proceedingsPerChunk = 1)
        val sink = RecordingSink()

        val first = connector.readPartitionChunk(BackfillPartition("term10", "K10"), null, sink)

        // Two proceedings in the fixture, one per chunk: not done yet.
        assertFalse(first.exhausted)
        assertEquals("1", first.nextCursor!![SejmConnector.CURSOR_LAST_PROCEEDING])
        assertNull(first.nextCursor!![Cursor.PARTITION_DONE])
        assertTrue(sink.externalIds.contains("term10/proceeding/1"))
        assertTrue(sink.externalIds.none { it == "term10/proceeding/2" })

        val second = connector.readPartitionChunk(
            BackfillPartition("term10", "K10"),
            first.nextCursor,
            RecordingSink(),
        )

        // Second chunk finishes the term and marks the cursor done, which is what
        // stops the dispatcher bringing this partition back.
        assertTrue(second.exhausted)
        assertEquals("2", second.nextCursor!![SejmConnector.CURSOR_LAST_PROCEEDING])
        assertEquals("true", second.nextCursor!![Cursor.PARTITION_DONE])
    }

    /**
     * Processes resume on an index offset, because a process number is not a position
     * in the index — the term's own ordering is, and only the offset can express where
     * a chunk stopped.
     */
    @Test
    fun `a partition resumes its processes from the index offset`() {
        val connector = connectorOf(FixtureHttpClient(), processesPerChunk = 2)
        val partition = BackfillPartition("term10", "Kadencja 10")

        val first = RecordingSink()
        val firstChunk = connector.readPartitionChunk(partition, cursor = null, sink = first)
        val resumeFrom = assertNotNull(firstChunk.nextCursor)

        assertEquals(listOf("term10/process/31", "term10/process/27"), first.processIds)
        assertEquals("2", resumeFrom[SejmConnector.CURSOR_PROCESS_OFFSET])
        assertFalse(firstChunk.exhausted, "one process is still unread")

        val second = RecordingSink()
        val secondChunk = connector.readPartitionChunk(partition, resumeFrom, second)

        assertEquals(listOf("term10/process/3"), second.processIds)
        assertEquals("3", assertNotNull(secondChunk.nextCursor)[SejmConnector.CURSOR_PROCESS_OFFSET])
        assertTrue(secondChunk.exhausted)
    }

    @Test
    fun `a malformed partition key is rejected`() {
        assertFailsWith<IllegalStateException> {
            connectorOf(FixtureHttpClient())
                .readPartitionChunk(BackfillPartition("kadencja-X", "?"), null, RecordingSink())
        }
    }

    /** Re-emits every object with its keys in the opposite order. */
    private fun reverseKeys(body: ByteArray): ByteArray {
        fun flip(node: Any?): Any? = when (node) {
            is Map<*, *> -> node.entries.reversed().associate { it.key to flip(it.value) }
            is List<*> -> node.map(::flip)
            else -> node
        }
        return json.writeValueAsBytes(flip(json.readValue(body, Any::class.java)))
    }

    /**
     * Takes the dates off the unnumbered sitting, leaving it with nothing to be
     * addressed by — a shape the API has never produced, and the only one this
     * connector cannot archive.
     */
    private fun stripDatesFromUnnumbered(body: ByteArray): ByteArray {
        val decoded = json.readValue(body, Any::class.java)
        if (decoded !is List<*> || decoded.none { it is Map<*, *> && it["number"] == 0 }) return body

        return json.writeValueAsBytes(
            decoded.map { sitting ->
                if (sitting is Map<*, *> && sitting["number"] == 0) sitting.minus("dates") else sitting
            },
        )
    }

    /** Serves the recorded responses, and counts what was asked for. */
    private class FixtureHttpClient(
        private val refusePathPattern: Regex? = null,
        private val rewrite: (ByteArray) -> ByteArray = { it },
    ) : SourceHttpClient {
        val requestedPaths = mutableListOf<String>()

        override fun fetch(request: HttpFetch): HttpOutcome {
            val path = request.url.path
            requestedPaths += path

            if (refusePathPattern?.matches(path) == true) {
                return HttpOutcome.Refused(RefusalReason.ROBOTS_DISALLOWED, "blocked in test")
            }

            val fixture = when {
                path == "/sejm/term" -> "term.json"
                path == "/sejm/term10/prints" -> "term10-prints.json"
                path == "/sejm/term10/clubs" -> "term10-clubs.json"
                path == "/sejm/term10/MP" -> "term10-mp.json"
                path == "/sejm/term10/proceedings" -> "term10-proceedings.json"
                path.matches(Regex("/sejm/term10/votings/\\d+")) -> "term10-votings-1.json"
                path == "/sejm/term10/processes" -> return served(indexPage(read("term10-processes.json"), request.url.query))
                path.matches(Regex("/sejm/term10/processes/\\d+")) ->
                    "term10-process-${path.substringAfterLast('/')}.json"
                else -> return HttpOutcome.Failed(404, "no fixture for $path")
            }

            return served(read(fixture))
        }

        private fun read(fixture: String): ByteArray =
            requireNotNull(javaClass.getResourceAsStream("/fixtures/sejm/$fixture")) {
                "Missing fixture $fixture"
            }.use { it.readBytes() }

        private fun served(body: ByteArray) = HttpOutcome.Fetched(
            body = rewrite(body),
            contentType = "application/json",
            etag = null,
            lastModified = null,
        )

        /** Slices the recorded index the way the API slices it. */
        private fun indexPage(body: ByteArray, query: String?): ByteArray {
            val parameters = query.orEmpty().split('&')
                .mapNotNull { it.split('=').takeIf { parts -> parts.size == 2 } }
                .associate { (name, value) -> name to value }
            val offset = parameters["offset"]?.toInt() ?: 0
            val limit = parameters["limit"]?.toInt() ?: Int.MAX_VALUE

            val mapper = JsonMapper.builder().build()
            val recorded = mapper.readValue(body, List::class.java)

            return mapper.writeValueAsBytes(recorded.drop(offset).take(limit))
        }
    }

    private class RecordingSink : RawDocumentSink {
        val accepted = mutableListOf<RawPayload>()
        val warnings = mutableListOf<SchemaWarning>()

        val externalIds: List<String> get() = accepted.map { it.externalId.value }

        val processIds: List<String> get() = externalIds.filter { it.contains("/process/") }

        override fun archive(payload: RawPayload): SinkOutcome {
            accepted += payload
            return SinkOutcome.STORED
        }

        override fun recordSchemaWarning(warning: SchemaWarning) {
            warnings += warning
        }
    }

    /** Prints and processes both already read: the state of a poll with nothing to do. */
    private fun quietCursor() = Cursor(
        IngestionMode.INCREMENTAL,
        mapOf(
            SejmConnector.CURSOR_PRINTS_LAST_CHANGED to PRINTS_LAST_CHANGED,
            SejmConnector.CURSOR_PROCESSES_CHANGED_THROUGH to NEWEST_PROCESS_CHANGE,
        ),
    )

    private companion object {
        val BASE_URL: URI = URI.create("https://api.sejm.gov.pl")

        const val PRINTS_LAST_CHANGED = "2026-08-17T14:38:12"

        /** The newest of the three recorded processes: druk 27, still in progress. */
        const val NEWEST_PROCESS_CHANGE = "2024-02-08T19:29:18"
    }
}
