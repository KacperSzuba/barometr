package pl.barometr.search.internal

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.Refresh
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.barometr.testing.ElasticsearchTestNode
import java.net.URI
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The analyser, against a real node with the Polish plugin in it.
 *
 * This is the test the whole module rests on. Search in Polish either folds inflection
 * or it finds nothing, and whether it does is a property of a plugin's trained stemmer
 * rather than of anything written here — so it is measured, not assumed. The stock
 * `polish` analyser fails the specification's own acceptance query, which is why the
 * index carries an override list and why the list is checked here rather than trusted.
 */
class LegislativeIndexTest {

    private val maintenance = LegislativeIndexMaintenance(client, Clock.systemUTC())

    /**
     * Its own index, deliberately not the alias. What is under test here is the
     * analyser, and borrowing the live alias would make this class depend on whether
     * the rebuild test had run first.
     */
    private val index: String by lazy { maintenance.createIndex() }

    @Test
    fun `every form of the three words a legislative title is built from folds together`() {

        // The stemmer alone reads "ustawy" as a verb, "zmiana" as another, and splits
        // "projekcie" off from "projekt". Left uncorrected, a search for a bill about
        // amending an act matches almost nothing.
        assertEquals(listOf("ustawa"), analyse("ustawy"))
        assertEquals(listOf("ustawa"), analyse("ustaw"))
        assertEquals(listOf("ustawa"), analyse("ustawami"))
        assertEquals(listOf("projekt"), analyse("projekcie"))
        assertEquals(listOf("projekt"), analyse("projekty"))
        assertEquals(listOf("zmiana"), analyse("zmiana"))
        assertEquals(listOf("zmiana"), analyse("zmianie"))
    }

    /**
     * What the stemmer already does correctly, checked so the override list stays as
     * short as the evidence justifies rather than growing by superstition.
     */
    @Test
    fun `words the stemmer handles are left to it`() {

        assertEquals(analyse("uchwała"), analyse("uchwały"))
        assertEquals(analyse("przepis"), analyse("przepisów"))
        assertEquals(analyse("energia"), analyse("energii"))
        assertEquals(analyse("cena"), analyse("cenach"))
    }

    /** The specification's own acceptance query, run against the index it describes. */
    @Test
    fun `a query in one case finds a title written in another`() {
        index("1", "Ustawa z dnia 17 lipca 2026 r. o cenach energii elektrycznej")
        index("2", "Rządowy projekt ustawy o zmianie ustawy o podatku dochodowym")

        val found = search("ustawy o cenach energii")

        assertEquals("1", found.first(), "the act titled in the nominative, asked for in the genitive")
        assertTrue(search("zmianie ustawy o podatku").first() == "2")
        assertTrue(search("projekty zmian ustaw").first() == "2")
    }

    @Test
    fun `Polish stopwords are dropped rather than searched for`() {

        assertTrue(analyse("o w z na").isEmpty(), "prepositions carry no meaning to match on")
    }

    // ——— Harness ————————————————————————————————————————————————————————————

    private fun analyse(text: String): List<String> =
        client.indices().analyze { request ->
            request.index(index).analyzer(POLISH_LEGAL).text(text)
        }.tokens().map { it.token() }

    private fun index(id: String, title: String) {
        client.index { request ->
            request.index(index).id(id).document(mapOf("title" to title)).refresh(Refresh.True)
        }
    }

    private fun search(query: String): List<String> {
        val match = MatchQuery.of { it.field("title").query(query) }._toQuery()

        return client.search({ request -> request.index(index).query(match) }, Map::class.java)
            .hits().hits().mapNotNull { it.id() }
    }

    companion object {
        private const val POLISH_LEGAL = "polish_legal"

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
