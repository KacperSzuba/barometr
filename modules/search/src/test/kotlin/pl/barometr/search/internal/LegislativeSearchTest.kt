package pl.barometr.search.internal

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.testing.ElasticsearchTestNode
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Searching the way somebody actually searches: a phrase half-remembered from a
 * document, a number pasted out of an e-mail, and then a facet clicked to narrow it.
 */
class LegislativeSearchTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC)
    private val entries = LegislativeEntries(clock)
    private val writer = LegislativeIndexWriter(client)
    private val maintenance = LegislativeIndexMaintenance(client, Clock.systemUTC())
    private val search = LegislativeSearch(client)

    @BeforeEach
    fun indexTheCorpus() {
        maintenance.pointAliasAt(maintenance.createIndex())
        writer.writeAll(
            LegislativeIndex.ALIAS,
            listOf(
                entry("act:1", IndexedEntry.ACT, "Ustawa z dnia 17 lipca 2026 r. o cenach energii elektrycznej")
                    .copy(actType = "Ustawa", eli = "DU/2026/1074", identifiers = listOf("DU/2026/1074")),
                entry("draft:1", IndexedEntry.DRAFT, "Rządowy projekt ustawy o zmianie ustawy o cenach energii")
                    .copy(initiator = "rzadowy", stage = "ii_czytanie", identifiers = listOf("term10/print/424", "UD383")),
                entry("draft:2", IndexedEntry.DRAFT, "Poselski projekt ustawy o zmianie Kodeksu pracy")
                    .copy(initiator = "poselski", stage = "i_czytanie", identifiers = listOf("term10/print/999")),
                entry("act:2", IndexedEntry.ACT, "Rozporządzenie w sprawie opłat za czynności notarialne")
                    .copy(actType = "Rozporządzenie", eli = "DU/2026/900", identifiers = listOf("DU/2026/900")),
            ),
        )
        client.indices().refresh { it.index(LegislativeIndex.ALIAS) }
    }

    /** The specification's acceptance query, asked of the assembled search. */
    @Test
    fun `a phrase in the wrong case still finds the act`() {
        val found = search.search(SearchQuery(text = "ustawy o cenach energii"))

        assertTrue(found.total >= 2)
        assertTrue(found.hits.first().id in setOf("act:1", "draft:1"))
        assertTrue(found.hits.none { it.id == "act:2" }, "notarial fees are about something else")
    }

    /**
     * How somebody looks for a draft they have seen referred to in a document: they
     * paste the number.
     */
    @Test
    fun `a print number finds the draft it belongs to`() {
        val found = search.search(SearchQuery(text = "term10/print/424"))

        assertEquals("draft:1", found.hits.first().id)
    }

    /**
     * A coincidental match on an identifier must never outrank the subject somebody
     * asked about, which is why the title outweighs it by a wide margin rather than a
     * nudge.
     */
    @Test
    fun `the title outranks an identifier`() {
        val found = search.search(SearchQuery(text = "Kodeksu pracy"))

        assertEquals("draft:2", found.hits.first().id)
    }

    @Test
    fun `a filter narrows without touching the ranking`() {
        val everything = search.search(SearchQuery(text = "ustawy o cenach energii"))
        val draftsOnly = search.search(SearchQuery(text = "ustawy o cenach energii", kinds = setOf("draft")))

        assertTrue(draftsOnly.hits.all { it.kind == "draft" })
        assertEquals(
            everything.hits.first { it.kind == "draft" }.score,
            draftsOnly.hits.first().score,
            "filtering is not a query: it must not move the score",
        )
    }

    @Test
    fun `facets say what the same search could be narrowed by`() {
        val found = search.search(SearchQuery(text = "projekt ustawy"))

        // Two drafts and, because "ustawy" stems to "ustawa", the act itself.
        assertEquals(mapOf("draft" to 2L, "act" to 1L), found.facets["kind"])
        assertEquals(setOf("poselski", "rzadowy"), found.facets["initiator"]?.keys)
        assertEquals(setOf("i_czytanie", "ii_czytanie"), found.facets["stage"]?.keys)
    }

    @Test
    fun `the matching words come back marked`() {
        val found = search.search(SearchQuery(text = "cenach energii"))

        val highlighted = assertNotNull(found.hits.first().highlightedTitle)
        assertTrue(highlighted.contains("<em>"), "a reader has to see why this was a hit")
    }

    @Test
    fun `a search with no text is a browse of everything, narrowed by facets`() {
        val found = search.search(SearchQuery(text = null, actTypes = setOf("Ustawa")))

        assertEquals(1, found.total)
        assertEquals("act:1", found.hits.single().id)
    }

    private fun entry(id: String, kind: String, title: String) = IndexedEntry(
        id = id,
        kind = kind,
        title = title,
        indexedAt = clock.instant().toString(),
    )

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
