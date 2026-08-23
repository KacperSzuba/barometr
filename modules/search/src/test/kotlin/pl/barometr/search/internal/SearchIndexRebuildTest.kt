package pl.barometr.search.internal

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.LegislativeSignals
import pl.barometr.legislative.api.PublishedAct
import pl.barometr.legislative.api.TrackedDraft
import pl.barometr.shared.Eli
import pl.barometr.shared.Ids
import pl.barometr.testing.ElasticsearchTestNode
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Rebuilding the index from what the database holds, which is the thing that makes a
 * second datastore acceptable at all: nothing here is a source of truth, so it can be
 * thrown away and made again.
 *
 * Against a real node, because what is being tested is the switch — an alias moving
 * between two indices without a moment where a search finds nothing — and no fake of
 * Elasticsearch would tell us whether that works.
 */
@ResourceLock(ElasticsearchTestNode.INDEX_LOCK)
class SearchIndexRebuildTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC)
    private val catalog = FakeCatalog()
    private val maintenance = LegislativeIndexMaintenance(client, Clock.systemUTC())
    private val rebuild = SearchIndexRebuild(
        catalog = catalog,
        entries = LegislativeEntries(clock),
        writer = LegislativeIndexWriter(client),
        maintenance = maintenance,
    )

    @Test
    fun `a rebuild indexes everything the database holds and points the alias at it`() {
        val report = rebuild.rebuild()
        refresh()

        assertEquals(2, report.acts)
        assertEquals(1, report.drafts)
        assertEquals(listOf(report.index), indicesBehindAlias())
        assertTrue(search("cenach energii").isNotEmpty(), "an act indexed by the rebuild is findable")
        assertTrue(search("projekt zmiany ustawy").any { it.startsWith("draft:") })
    }

    /**
     * The whole point of building into a new index: the previous one keeps answering
     * until the alias moves, and only then is it dropped.
     */
    @Test
    fun `a second rebuild replaces the first without leaving it behind`() {
        val first = rebuild.rebuild()
        val second = rebuild.rebuild()
        refresh()

        assertFalse(first.index == second.index)
        assertEquals(listOf(second.index), indicesBehindAlias())
        assertFalse(indexExists(first.index), "the superseded index is dropped, not accumulated")
        assertTrue(search("cenach energii").isNotEmpty(), "and search never stopped answering")
    }

    /**
     * The identifiers people actually quote — a print number, a programme number, an
     * ELI — are searchable, because that is how somebody looks for a draft they have
     * seen referred to in a document.
     */
    @Test
    fun `a draft can be found by the number people quote it by`() {
        rebuild.rebuild()
        refresh()

        assertTrue(search("term10/print/424", "identifiers").any { it.startsWith("draft:") })
        assertTrue(search("DU/2026/1074", "identifiers").any { it.startsWith("act:") })
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    private fun refresh() = client.indices().refresh { it.index(LegislativeIndex.ALIAS) }

    private fun indicesBehindAlias(): List<String> =
        client.indices().getAlias { it.name(LegislativeIndex.ALIAS) }.aliases().keys.toList()

    private fun indexExists(index: String): Boolean = client.indices().exists { it.index(index) }.value()

    private fun search(query: String, field: String = "title"): List<String> {
        val match = MatchQuery.of { it.field(field).query(query) }._toQuery()

        return client.search({ it.index(LegislativeIndex.ALIAS).query(match) }, Map::class.java)
            .hits().hits().mapNotNull { it.id() }
    }

    private class FakeCatalog : LegislativeCatalog {
        private val acts = listOf(
            PublishedAct(
                id = ActId(Ids.next()),
                eli = Eli("DU/2026/1074"),
                title = "Ustawa z dnia 17 lipca 2026 r. o cenach energii elektrycznej",
                type = "Ustawa",
                publisher = "DU",
                announcedOn = LocalDate.parse("2026-08-10"),
                inForceFrom = LocalDate.parse("2027-02-11"),
            ),
            PublishedAct(
                id = ActId(Ids.next()),
                eli = Eli("MP/2026/12"),
                title = "Uchwała Sejmu w sprawie powołania członka Rady",
                type = "Uchwała",
                publisher = "MP",
                announcedOn = LocalDate.parse("2026-01-20"),
                inForceFrom = null,
            ),
        )

        private val drafts = listOf(
            TrackedDraft(
                id = DraftId(Ids.next()),
                title = "Rządowy projekt ustawy o zmianie ustawy o cenach energii",
                initiator = "rzadowy",
                term = 10,
                startedOn = LocalDate.parse("2026-03-01"),
                closedOn = null,
                outcome = null,
                currentStage = "ii_czytanie",
                identifiers = listOf("term10/print/424", "UD383"),
            ),
        )

        override fun actById(id: ActId) = acts.firstOrNull { it.id == id }

        override fun actByEli(eli: Eli) = acts.firstOrNull { it.eli == eli }

        override fun draftById(id: DraftId) = drafts.firstOrNull { it.id == id }

        /** Nothing here ranks anything; the signals are somebody else's question. */
        override fun signalsForDraft(id: DraftId): LegislativeSignals? = null

        override fun actsAfter(after: ActId?, limit: Int) =
            acts.dropWhile { after != null && it.id.value <= after.value }.take(limit)

        override fun draftsAfter(after: DraftId?, limit: Int) =
            drafts.dropWhile { after != null && it.id.value <= after.value }.take(limit)
    }

    companion object {
        private lateinit var client: ElasticsearchClient

        @JvmStatic
        @BeforeAll
        fun connect() {
            val address = URI.create(ElasticsearchTestNode.httpAddress)
            val rest = Rest5Client.builder(HttpHost(address.scheme, address.host, address.port)).build()
            client = ElasticsearchClient(Rest5ClientTransport(rest, JacksonJsonpMapper()))
        }
    }
}
