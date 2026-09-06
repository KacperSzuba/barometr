package pl.barometr.search.internal

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import org.apache.hc.core5.http.HttpHost
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.ElasticsearchTestNode
import java.net.URI
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
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

    @TempDir
    lateinit var blobRoot: Path

    private val clock = Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC)
    private val catalog = FakeCatalog()

    private lateinit var archive: FakeArchive
    private lateinit var rebuild: SearchIndexRebuild

    @BeforeEach
    fun fillTheArchive() {
        val blobs = FilesystemBlobStore(blobRoot)
        archive = FakeArchive(blobs)
        archive.archive(
            kind = FILED_DOCUMENT,
            externalId = "projekt/12409051/12345/katalog/1/dokument/9",
            title = null,
            text = "Projekt ustawy o zmianie ustawy o cenach energii. Art. 1. W ustawie wprowadza sie " +
                "zmiany dotyczace taryf dla odbiorcow przemyslowych.",
        )
        // A payload of the kind text is extracted from and nobody should be searching:
        // the JSON a register's API returned.
        archive.archive(
            kind = DocumentKind("print"),
            externalId = "term10/print/424",
            title = "Rzadowy projekt ustawy",
            text = "{\"title\":\"Rzadowy projekt ustawy o cenach energii\",\"number\":\"424\"}",
        )

        rebuild = SearchIndexRebuild(
            catalog = catalog,
            documents = archive,
            blobs = blobs,
            entries = LegislativeEntries(clock),
            documentEntries = DocumentEntries(clock),
            writer = LegislativeIndexWriter(client),
            maintenance = LegislativeIndexMaintenance(client, Clock.systemUTC()),
        )
    }

    @Test
    fun `a rebuild indexes everything the database holds and points the alias at it`() {
        val report = rebuild.rebuild()
        refresh()

        assertEquals(2, report.acts)
        assertEquals(1, report.drafts)
        assertEquals(1, report.documents, "the filed document, and not the API payload beside it")
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

    /**
     * The rule that keeps a search for a word from returning the JSON that happens to
     * contain it: text is extracted from everything the archive holds, and only the
     * kinds somebody wrote for people to read are indexed.
     */
    @Test
    fun `a rebuild indexes prose and leaves the register's own payloads out`() {
        rebuild.rebuild()
        refresh()

        assertTrue(search("taryf odbiorcow przemyslowych", "content").isNotEmpty(), "the bill's text is searchable")
        assertEquals(1, search("ustawie", "content").size, "one document holds prose, and only it is indexed")
    }

    private fun indexExists(index: String): Boolean = client.indices().exists { it.index(index) }.value()

    private fun search(query: String, field: String = "title"): List<String> {
        val match = MatchQuery.of { it.field(field).query(query) }._toQuery()

        return client.search({ it.index(LegislativeIndex.ALIAS).query(match) }, Map::class.java)
            .hits().hits().mapNotNull { it.id() }
    }

    companion object {
        private val FILED_DOCUMENT = DocumentKind("rcl-filed-document")

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
