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
import kotlin.test.assertTrue

/**
 * What a subscription means, as opposed to what a search means.
 *
 * A person watching a phrase is asking to be told about a narrow thing, so every word
 * has to appear; a person searching is asking to be shown the best of a vague thing.
 * The two share an analyser and differ in that one operator, and both halves of that
 * sentence are worth a test — the strictness, and the stemming that survives it.
 */
class SubscriptionMatchingTest {

    private val clock = Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC)
    private val maintenance = LegislativeIndexMaintenance(client, Clock.systemUTC())
    private val writer = LegislativeIndexWriter(client)
    private val titles = TitleSearchAdapter(client)
    private val analysis = TextAnalysisAdapter(client)

    @BeforeEach
    fun indexTheCorpus() {
        maintenance.pointAliasAt(maintenance.createIndex())
        writer.writeAll(
            LegislativeIndex.ALIAS,
            listOf(
                entry("act:11", IndexedEntry.ACT, "Ustawa Prawo budowlane").copy(eli = "DU/2024/1222"),
                entry("act:12", IndexedEntry.ACT, "Ustawa o prawach konsumenta").copy(eli = "DU/2024/17"),
                entry("draft:11", IndexedEntry.DRAFT, "Rządowy projekt ustawy o dronach"),
            ),
        )
        client.indices().refresh { it.index(LegislativeIndex.ALIAS) }
    }

    /**
     * The failure this operator exists to prevent: somebody watching *prawo budowlane*
     * being told about every act carrying the word *prawo*.
     */
    @Test
    fun `a watched phrase needs every one of its words`() {
        val found = titles.titlesMatching("prawo budowlane", 10)

        assertEquals(listOf("DU/2024/1222"), found.map { it.eli })
    }

    @Test
    fun `an inflected form still matches, because the stemmer is the same one`() {
        val found = titles.titlesMatching("prawa budowlanego", 10)

        assertEquals(listOf("DU/2024/1222"), found.map { it.eli })
    }

    @Test
    fun `a phrase nothing carries finds nothing rather than the nearest thing`() {
        assertEquals(emptyList(), titles.titlesMatching("prawo lotnicze kosmiczne", 10))
    }

    @Test
    fun `the match carries the identifier of the thing itself, not the index's`() {
        val found = titles.titlesMatching("drony", 10).single()

        assertEquals("draft", found.kind)
        assertEquals("11", found.id)
    }

    /**
     * The other direction: a caller holding one title and one phrase has to be able to
     * decide the same question without a search, and get the same answer.
     */
    @Test
    fun `stemming a title and a phrase agrees with what the search found`() {
        val title = analysis.stemsOf("Ustawa Prawo budowlane")
        val phrase = analysis.stemsOf("prawa budowlanego")

        assertTrue(title.containsAll(phrase), "$phrase should be inside $title")
        assertTrue(!title.containsAll(analysis.stemsOf("prawo lotnicze")))
    }

    @Test
    fun `stopwords are dropped on both sides, so they cannot decide a match`() {
        assertEquals(emptyList(), analysis.stemsOf("o w na nie"))
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
