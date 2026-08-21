package pl.barometr.connectors.isap

import org.junit.jupiter.api.Test
import org.springframework.web.util.UriComponentsBuilder
import pl.barometr.connectors.support.CanonicalJsonPayload
import pl.barometr.http.HttpFetch
import pl.barometr.http.HttpOutcome
import pl.barometr.http.RefusalReason
import pl.barometr.http.SourceHttpClient
import pl.barometr.ingestion.api.BackfillPartition
import pl.barometr.ingestion.api.Cursor
import pl.barometr.ingestion.api.RawDocumentSink
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.ingestion.api.SinkOutcome
import pl.barometr.ingestion.api.SourceAccessDeniedException
import pl.barometr.sources.api.IngestionMode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract test against responses recorded from the live ELI API.
 *
 * Recorded rather than written by hand, because the point is to notice when the
 * *source* changes shape — a stub only ever confirms what we already believed.
 * Refreshing the fixtures and watching this test fail is how a silent API change
 * becomes visible before it becomes missing data.
 *
 * The fixtures hold whole result sets, and [FixtureHttpClient] serves them a page at
 * a time the way the API does. That is what lets paging and exhaustion be tested
 * against a real journal-year — Dziennik Ustaw 1918, seventy-six acts — without
 * recording thirty-eight separate pages of it.
 */
class IsapConnectorContractTest {

    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    private fun connectorOf(
        http: SourceHttpClient,
        pageSize: Int = IsapConnector.DEFAULT_PAGE_SIZE,
        clock: Clock = FIXED_CLOCK,
    ) = IsapConnector(
        api = IsapApiClient(http, BASE_URL, json),
        payloads = CanonicalJsonPayload(json),
        clock = clock,
        pageSize = pageSize,
    )

    // ——— Incremental ————————————————————————————————————————————————————————

    @Test
    fun `a first pass archives one document per act in both journals`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()

        connectorOf(http).readChangesSince(cursor = null, sink = sink)

        // 74 positions of Dziennik Ustaw and 87 of Monitor Polski, each its own
        // document: archiving the page instead would make one corrected act re-store
        // every other act published that fortnight.
        assertEquals(161, sink.accepted.size)
        assertTrue(sink.externalIds.contains("DU/2026/1106"))
        assertTrue(sink.externalIds.contains("MP/2026/836"))
        // Three requests for a fortnight of both journals: the index, and one page
        // each. The listing carries full metadata, so there is no per-act follow-up.
        assertEquals(3, http.requested.size)
    }

    @Test
    fun `the window starts a fortnight behind the last pass`() {
        val http = FixtureHttpClient()

        connectorOf(http).readChangesSince(cursor = null, sink = RecordingSink())

        // Clock at 2026-08-15, lookback fourteen days. Publication in the journal,
        // not the date on the act: the two differ by weeks.
        assertTrue(http.requested.all { it == PUBLISHERS || it.contains("pubDateFrom=2026-08-01") })
    }

    /**
     * The behaviour that makes an hourly poll affordable. The first page is the
     * newest end of the window, so a journal nothing has touched since the last pass
     * is settled in one request instead of downloading the fortnight again.
     */
    @Test
    fun `journals with nothing newer than the cursor cost one request each`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()

        val result = connectorOf(http).readChangesSince(cursorAt(NEWEST_CHANGE), sink)

        assertEquals(0, sink.accepted.size)
        assertTrue(result.sourceUnchanged)
        assertEquals(3, http.requested.size)
    }

    /**
     * The check is per journal, not per source: Monitor Polski moving is no reason
     * to re-read Dziennik Ustaw, and the two are indexed at different minutes.
     */
    @Test
    fun `only the journal that moved is read again`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()

        // Between the two journals' newest stamps: 10:16:17 for DU, 10:18:15 for MP.
        val result = connectorOf(http).readChangesSince(cursorAt("2026-08-20T10:17:00"), sink)

        assertEquals(87, sink.accepted.size)
        assertTrue(sink.externalIds.all { it.startsWith("MP/") })
        assertFalse(result.sourceUnchanged)
    }

    @Test
    fun `the cursor records the day read and the newest change stamp`() {
        val result = connectorOf(FixtureHttpClient())
            .readChangesSince(cursor = null, sink = RecordingSink())

        val cursor = result.nextCursor!!
        assertEquals("2026-08-15", cursor[IsapConnector.CURSOR_PUBLISHED_THROUGH])
        assertEquals(NEWEST_CHANGE, cursor[IsapConnector.CURSOR_LAST_CHANGE])
    }

    @Test
    fun `a window longer than one page is read to its end`() {
        val http = FixtureHttpClient()
        val sink = RecordingSink()

        connectorOf(http, pageSize = 50).readChangesSince(cursor = null, sink = sink)

        assertEquals(161, sink.accepted.size)
        // Two pages of each journal, and the index.
        assertEquals(5, http.requested.size)
        assertTrue(http.requested.any { it.contains("offset=50") })
    }

    /**
     * A refused journal is a gap, not a catastrophe: the other one still arrives and
     * the next hourly pass tries again.
     */
    @Test
    fun `a refused journal is recorded while the other is still archived`() {
        val http = FixtureHttpClient(refuse = Regex(".*publisher=MP.*"))
        val sink = RecordingSink()

        val result = connectorOf(http).readChangesSince(cursor = null, sink = sink)

        assertEquals(74, sink.accepted.size)
        assertTrue(sink.externalIds.all { it.startsWith("DU/") })
        assertTrue(sink.warnings.any { it.kind == SchemaWarning.Kind.ACCESS_DENIED })
        // Refusal is not silence. Claiming the source was unchanged here would turn
        // an outage into a healthy idle poll, which is what the volume check exists
        // to catch.
        assertFalse(result.sourceUnchanged)
    }

    @Test
    fun `a refused journal holds the window open instead of advancing past it`() {
        val http = FixtureHttpClient(refuse = Regex(".*publisher=MP.*"))
        val aDayLater = Clock.fixed(Instant.parse("2026-08-16T00:00:00Z"), ZoneOffset.UTC)

        val result = connectorOf(http, clock = aDayLater).readChangesSince(
            cursor = Cursor(
                IngestionMode.INCREMENTAL,
                mapOf(IsapConnector.CURSOR_PUBLISHED_THROUGH to "2026-08-15"),
            ),
            sink = RecordingSink(),
        )

        // Still the day of the last clean pass, so tomorrow's window reaches back far
        // enough to cover what Monitor Polski published while we could not read it.
        assertEquals("2026-08-15", result.nextCursor!![IsapConnector.CURSOR_PUBLISHED_THROUGH])
    }

    /**
     * Different in kind from a refused journal: with no index there is nothing to
     * read at all, and a source we are forbidden from reading must look broken rather
     * than idle.
     */
    @Test
    fun `a refused index fails the run`() {
        val http = FixtureHttpClient(refuse = Regex(PUBLISHERS))

        val failure = assertFailsWith<SourceAccessDeniedException> {
            connectorOf(http).readChangesSince(null, RecordingSink())
        }

        assertEquals(PUBLISHERS, failure.resource)
    }

    /**
     * An item we cannot address is dropped, recorded, and — the part that matters —
     * still counted when the offset advances. Advancing by readable items instead
     * would re-request the same page forever.
     */
    @Test
    fun `an act without an address is recorded and skipped without stalling the walk`() {
        val http = FixtureHttpClient(rewrite = ::dropFirstAddress)
        val sink = RecordingSink()

        val result = connectorOf(http).readChangesSince(cursor = null, sink = sink)

        assertEquals(159, sink.accepted.size)
        assertEquals(2, sink.warnings.count { it.kind == SchemaWarning.Kind.MISSING_FIELD })
        assertTrue(sink.warnings.any { it.path == "items[0].ELI" })
        // Both journals still finished in one page each.
        assertEquals(3, http.requested.size)
        assertEquals(NEWEST_CHANGE, result.nextCursor!![IsapConnector.CURSOR_LAST_CHANGE])
    }

    /**
     * Payloads are re-serialised with sorted keys, so an act the source decides to
     * emit in a different field order still hashes to the same content address.
     * Without this, deduplication would depend on somebody else's serialiser never
     * changing.
     */
    @Test
    fun `field order in the response does not change the payload`() {
        val straight = RecordingSink()
        connectorOf(FixtureHttpClient()).readChangesSince(null, straight)

        val reordered = RecordingSink()
        connectorOf(FixtureHttpClient(rewrite = ::reverseKeys)).readChangesSince(null, reordered)

        val payloadsById = { sink: RecordingSink ->
            sink.accepted.associate { it.externalId.value to String(it.payload, Charsets.UTF_8) }
        }
        assertEquals(payloadsById(straight), payloadsById(reordered))
    }

    // ——— Backfill ———————————————————————————————————————————————————————————

    /**
     * The years come from the API, not from the requested window. Monitor Polski
     * begins in 1930, and a partition for its 1918 would be a replay that can never
     * finish.
     */
    @Test
    fun `partitions cover only the years a journal actually has`() {
        val partitions = connectorOf(FixtureHttpClient())
            .partitions(LocalDate.parse("1918-01-01"), LocalDate.parse("1918-12-31"))

        assertEquals(listOf("DU/1918"), partitions.map { it.key })
        assertEquals("Dziennik Ustaw 1918", partitions.single().label)
    }

    @Test
    fun `partitions run newest year first`() {
        val partitions = connectorOf(FixtureHttpClient())
            .partitions(LocalDate.parse("2025-01-01"), LocalDate.parse("2026-12-31"))

        assertEquals(listOf("DU/2026", "MP/2026", "DU/2025", "MP/2025"), partitions.map { it.key })
    }

    @Test
    fun `a partition is read one page at a time and resumes from its offset`() {
        val connector = connectorOf(FixtureHttpClient(), pageSize = 50)
        val partition = BackfillPartition("DU/1918", "Dziennik Ustaw 1918")

        val first = RecordingSink()
        val firstChunk = connector.readPartitionChunk(partition, cursor = null, sink = first)

        val resumeFrom = assertNotNull(firstChunk.nextCursor)
        assertEquals(50, first.accepted.size)
        assertFalse(firstChunk.exhausted)
        assertEquals("50", resumeFrom[IsapConnector.CURSOR_OFFSET])
        assertNull(resumeFrom[Cursor.PARTITION_DONE])

        val second = RecordingSink()
        val secondChunk = connector.readPartitionChunk(partition, resumeFrom, second)
        val finished = assertNotNull(secondChunk.nextCursor)

        // The year holds seventy-six acts; the second page finishes it and marks the
        // cursor done, which is what stops the dispatcher bringing the partition back.
        assertEquals(26, second.accepted.size)
        assertTrue(secondChunk.exhausted)
        assertEquals("76", finished[IsapConnector.CURSOR_OFFSET])
        assertEquals("true", finished[Cursor.PARTITION_DONE])
        assertTrue(second.externalIds.contains("DU/1918/1"))
    }

    /**
     * In a replay a refusal must not be swallowed: a partition that recorded a gap
     * and marked itself done would leave a year of a journal missing for good.
     */
    @Test
    fun `a refused partition fails instead of reporting itself finished`() {
        val http = FixtureHttpClient(refuse = Regex(".*year=1918.*"))

        assertFailsWith<SourceAccessDeniedException> {
            connectorOf(http).readPartitionChunk(
                BackfillPartition("DU/1918", "Dziennik Ustaw 1918"),
                cursor = null,
                sink = RecordingSink(),
            )
        }
    }

    @Test
    fun `a malformed partition key is rejected`() {
        assertFailsWith<IllegalStateException> {
            connectorOf(FixtureHttpClient())
                .readPartitionChunk(BackfillPartition("DU-1918", "?"), null, RecordingSink())
        }
    }

    // ——— Completeness ————————————————————————————————————————————————————————

    @Test
    fun `the declared volume of a year is the total the API states for it`() {
        val volumes = connectorOf(FixtureHttpClient())
            .declaredVolumes(BackfillPartition("DU/1918", "Dziennik Ustaw 1918"))

        val acts = volumes.single()
        assertEquals(76, acts.declaredCount)
        assertEquals("DU/1918/", acts.externalIdPrefix)
        // Stated for the whole year rather than counted from the page we walked,
        // which is what makes it evidence about the archive rather than about the
        // walk.
        assertTrue(acts.isAuthoritative)
    }

    // ——— Fixtures ————————————————————————————————————————————————————————————

    private fun cursorAt(change: String) = Cursor(
        IngestionMode.INCREMENTAL,
        mapOf(
            IsapConnector.CURSOR_PUBLISHED_THROUGH to "2026-08-15",
            IsapConnector.CURSOR_LAST_CHANGE to change,
        ),
    )

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
     * Takes the address off the first act of every listing, which the API has never
     * done — yet. The publisher index is not a listing and passes through untouched.
     */
    private fun dropFirstAddress(body: ByteArray): ByteArray {
        val page = json.readValue(body, Any::class.java) as? Map<*, *> ?: return body
        val items = (page["items"] as? List<*> ?: return body).toMutableList()
        items[0] = (items[0] as Map<*, *>).filterKeys { it != "ELI" }

        return json.writeValueAsBytes(
            page.mapValues { (field, value) -> if (field == "items") items else value },
        )
    }

    /**
     * Serves the recorded result sets a page at a time, and counts what was asked
     * for.
     *
     * [rewrite] is the seam for the two cases the source has not produced for us:
     * a reordered response, and an act served without an address.
     */
    private inner class FixtureHttpClient(
        private val refuse: Regex? = null,
        private val rewrite: (ByteArray) -> ByteArray = { it },
    ) : SourceHttpClient {
        val requested = mutableListOf<String>()

        override fun fetch(request: HttpFetch): HttpOutcome {
            val resource = request.url.path + (request.url.query?.let { "?$it" } ?: "")
            requested += resource

            if (refuse?.matches(resource) == true) {
                return HttpOutcome.Refused(RefusalReason.ROBOTS_DISALLOWED, "blocked in test")
            }
            if (request.url.path == PUBLISHERS) return served(read("publishers.json"))

            val query = UriComponentsBuilder.fromUri(request.url).build().queryParams
            val publisher = query.getFirst("publisher")
            val fixture = when {
                publisher == null -> return HttpOutcome.Failed(400, "no publisher in $resource")
                query.getFirst("year") != null -> "search-$publisher-${query.getFirst("year")}.json"
                else -> "search-$publisher-since-${query.getFirst("pubDateFrom")}.json"
            }

            return served(
                page(
                    read(fixture),
                    offset = query.getFirst("offset")?.toInt() ?: 0,
                    limit = query.getFirst("limit")?.toInt() ?: DEFAULT_LIMIT,
                ),
            )
        }

        /** Slices a recorded result set the way the API slices it. */
        private fun page(body: ByteArray, offset: Int, limit: Int): ByteArray {
            val recorded = json.readValue(body, Map::class.java)
            val slice = (recorded["items"] as List<*>).drop(offset).take(limit)

            return json.writeValueAsBytes(
                mapOf("count" to slice.size, "totalCount" to recorded["totalCount"], "items" to slice),
            )
        }

        private fun read(fixture: String): ByteArray =
            requireNotNull(javaClass.getResourceAsStream("/fixtures/isap/$fixture")) {
                "Missing fixture $fixture"
            }.use { it.readBytes() }

        private fun served(body: ByteArray) = HttpOutcome.Fetched(
            body = rewrite(body),
            contentType = "application/json",
            etag = null,
            lastModified = null,
        )
    }

    private class RecordingSink : RawDocumentSink {
        val accepted = mutableListOf<RawPayload>()
        val warnings = mutableListOf<SchemaWarning>()

        val externalIds: List<String> get() = accepted.map { it.externalId.value }

        override fun archive(payload: RawPayload): SinkOutcome {
            accepted += payload
            return SinkOutcome.STORED
        }

        override fun recordSchemaWarning(warning: SchemaWarning) {
            warnings += warning
        }
    }

    private companion object {
        val BASE_URL: URI = URI.create("https://api.sejm.gov.pl/eli")
        const val PUBLISHERS = "/eli/acts"
        const val DEFAULT_LIMIT = 100

        /** Recorded on 2026-08-21; Monitor Polski was indexed two minutes after DU. */
        const val NEWEST_CHANGE = "2026-08-20T10:18:15"

        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-15T00:00:00Z"), ZoneOffset.UTC)
    }
}
