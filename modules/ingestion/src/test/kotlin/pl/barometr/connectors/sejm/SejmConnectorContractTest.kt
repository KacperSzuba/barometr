package pl.barometr.connectors.sejm

import org.junit.jupiter.api.Test
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
    ) = SejmConnector(
        api = SejmApiClient(http, BASE_URL, json),
        payloads = CanonicalJsonPayload(json),
        proceedingsPerChunk = proceedingsPerChunk,
    )

    @Test
    fun `splits collections into one document per entity`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()

        connectorOf(http).fetch(cursor = null, sink = sink)

        // 5 prints + 2 clubs + 3 MPs + 2 proceedings + 2 x 3 votings
        assertEquals(18, sink.accepted.size)

        // Per-entity granularity is the whole design: with one document per
        // collection, a single amended print would re-store all 3205 of them.
        assertTrue(sink.externalIds.any { it == "term10/print/3005" })
        assertTrue(sink.externalIds.any { it.startsWith("term10/club/") })
        assertTrue(sink.externalIds.any { it.startsWith("term10/mp/") })
        assertTrue(sink.externalIds.contains("term10/proceeding/1"))
        assertTrue(sink.externalIds.any { it.matches(Regex("term10/proceeding/\\d+/voting/\\d+")) })
    }

    @Test
    fun `advances the cursor to the term's prints timestamp`() {
        val result = connectorOf(FixtureHttpClient())
            .fetch(cursor = null, sink = RecordingSink())

        val cursor = result.nextCursor!!
        assertEquals("10", cursor[SejmConnector.CURSOR_TERM])
        assertEquals("2026-08-17T14:38:12", cursor[SejmConnector.CURSOR_PRINTS_LAST_CHANGED])
    }

    /**
     * The behaviour that makes a fifteen-minute cycle affordable. This API has no
     * ETag and ignores `limit`, so without the cheap check every poll would pull
     * thousands of records to discover that nothing moved.
     */
    @Test
    fun `an unchanged term costs exactly one request`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()
        val cursor = Cursor(
            IngestionMode.INCREMENTAL,
            mapOf(SejmConnector.CURSOR_PRINTS_LAST_CHANGED to "2026-08-17T14:38:12"),
        )

        val result = connectorOf(http).fetch(cursor, sink)

        assertEquals(0, sink.accepted.size)
        assertEquals(listOf("/sejm/term"), http.requestedPaths)
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
        connector.fetch(null, straight)

        val reordered = RecordingSink()
        connectorOf(FixtureHttpClient(reverseKeys = true)).fetch(null, reordered)

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

        val result = connectorOf(http).fetch(null, sink)

        assertTrue(sink.warnings.any { it.kind == SchemaWarning.Kind.ACCESS_DENIED })
        // Prints, clubs, MPs and proceedings still arrived; only votings are missing.
        assertEquals(12, sink.accepted.size)
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
            connectorOf(http).fetch(null, RecordingSink())
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
            .fetchPartition(BackfillPartition("term10", "Kadencja 10"), cursor = null, sink = sink)

        assertEquals(18, sink.accepted.size)
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
            .fetchPartition(BackfillPartition("term10", "Kadencja 10"), cursor, sink)

        // Collections already done, so they are not fetched again.
        assertTrue(http.requestedPaths.none { it.endsWith("/prints") })
        assertTrue(http.requestedPaths.none { it.endsWith("/clubs") })
        assertTrue(http.requestedPaths.none { it.endsWith("/MP") })

        // Proceeding 1 was already processed; only 2 and its votings are read.
        assertTrue(sink.externalIds.none { it == "term10/proceeding/1" })
        assertTrue(sink.externalIds.contains("term10/proceeding/2"))
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

        val first = connector.fetchPartition(BackfillPartition("term10", "K10"), null, sink)

        // Two proceedings in the fixture, one per chunk: not done yet.
        assertFalse(first.exhausted)
        assertEquals("1", first.nextCursor!![SejmConnector.CURSOR_LAST_PROCEEDING])
        assertNull(first.nextCursor!![Cursor.PARTITION_DONE])
        assertTrue(sink.externalIds.contains("term10/proceeding/1"))
        assertTrue(sink.externalIds.none { it == "term10/proceeding/2" })

        val second = connector.fetchPartition(
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

    @Test
    fun `a malformed partition key is rejected`() {
        assertFailsWith<IllegalStateException> {
            connectorOf(FixtureHttpClient())
                .fetchPartition(BackfillPartition("kadencja-X", "?"), null, RecordingSink())
        }
    }

    /** Serves the recorded responses, and counts what was asked for. */
    private class FixtureHttpClient(
        private val reverseKeys: Boolean = false,
        private val refusePathPattern: Regex? = null,
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
                else -> return HttpOutcome.Failed(404, "no fixture for $path")
            }

            val body = requireNotNull(javaClass.getResourceAsStream("/fixtures/sejm/$fixture")) {
                "Missing fixture $fixture"
            }.use { it.readBytes() }

            return HttpOutcome.Fetched(
                body = if (reverseKeys) reorder(body) else body,
                contentType = "application/json",
                etag = null,
                lastModified = null,
            )
        }

        /** Re-emits every object with its keys in the opposite order. */
        private fun reorder(body: ByteArray): ByteArray {
            val mapper = JsonMapper.builder().build()
            fun flip(node: Any?): Any? = when (node) {
                is Map<*, *> -> node.entries.reversed().associate { it.key to flip(it.value) }
                is List<*> -> node.map(::flip)
                else -> node
            }
            return mapper.writeValueAsBytes(flip(mapper.readValue(body, Any::class.java)))
        }
    }

    private class RecordingSink : RawDocumentSink {
        val accepted = mutableListOf<RawPayload>()
        val warnings = mutableListOf<SchemaWarning>()

        val externalIds: List<String> get() = accepted.map { it.externalId.value }

        override fun accept(payload: RawPayload): SinkOutcome {
            accepted += payload
            return SinkOutcome.STORED
        }

        override fun warn(warning: SchemaWarning) {
            warnings += warning
        }
    }

    private companion object {
        val BASE_URL: URI = URI.create("https://api.sejm.gov.pl")
    }
}
