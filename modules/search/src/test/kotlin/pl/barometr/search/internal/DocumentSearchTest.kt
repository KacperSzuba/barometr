package pl.barometr.search.internal

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.json.jackson.JacksonJsonpMapper
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Searching what the documents say, rather than what they are called.
 *
 * The question this closes: everything a bill actually contains — its text, its
 * justification, the impact assessment — was archived, extracted and diffable, and a
 * phrase from any of it matched nothing. A title is forty words; the file is forty
 * pages.
 */
@ResourceLock(ElasticsearchTestNode.INDEX_LOCK)
class DocumentSearchTest {

    @TempDir
    lateinit var blobRoot: Path

    private val clock = Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC)
    private val catalog = FakeCatalog()

    private lateinit var archive: FakeArchive
    private lateinit var indexer: DocumentIndexer
    private lateinit var search: LegislativeSearch

    @BeforeEach
    fun indexTheArchive() {
        val blobs = FilesystemBlobStore(blobRoot)
        val maintenance = LegislativeIndexMaintenance(client, Clock.systemUTC())
        maintenance.pointAliasAt(maintenance.createIndex())

        archive = FakeArchive(blobs)
        indexer = DocumentIndexer(
            documents = archive,
            blobs = blobs,
            entries = DocumentEntries(clock),
            writer = LegislativeIndexWriter(client),
            meters = SimpleMeterRegistry(),
        )
        search = LegislativeSearch(client, catalog)
    }

    @Test
    fun `a phrase that appears only inside a filed document finds it`() {
        indexBill()

        val found = search.search(SearchQuery(text = "fotowoltaicznych mikroinstalacji"))

        assertEquals(1, found.total)
        assertEquals(IndexedEntry.DOCUMENT, found.hits.single().kind)
    }

    /**
     * A list of files that mention a phrase is a list of things to open; a list of
     * sentences that mention it is an answer.
     */
    @Test
    fun `the sentence that matched comes back with it`() {
        indexBill()

        val hit = search.search(SearchQuery(text = "mikroinstalacji")).hits.single()

        val passage = assertNotNull(hit.passages.firstOrNull(), "a text hit without its passage says nothing")
        assertTrue(passage.contains("<em>"), "the matching words are marked")
        assertTrue("mikroinstalacj" in passage.lowercase())
    }

    /**
     * The link a reader follows from a passage back to the bill — read at query time,
     * because it is commonly made after the file was indexed.
     */
    @Test
    fun `a document hit says which draft the file was filed under`() {
        val extracted = indexBill()
        catalog.fileUnder(extracted.documentId, catalog.drafts.single(), fileName = "Projekt ustawy.pdf")

        val hit = search.search(SearchQuery(text = "mikroinstalacji")).hits.single()

        val filedUnder = assertNotNull(hit.filedUnder)
        assertEquals(catalog.drafts.single().id, filedUnder.draftId)
        assertEquals("Projekt ustawy.pdf", filedUnder.fileName)
    }

    /** And the same hit before anybody has joined the file to a draft, which is the ordinary case. */
    @Test
    fun `a document no draft names yet is still searchable`() {
        indexBill()

        val hit = search.search(SearchQuery(text = "mikroinstalacji")).hits.single()

        assertEquals(null, hit.filedUnder)
        assertTrue(hit.passages.isNotEmpty(), "the passage is what makes it useful in the meantime")
    }

    /**
     * Text is extracted from every archived payload, including the JSON a register's API
     * returned. Indexing that would bury every bill under the machine-readable copies of
     * its own metadata.
     */
    @Test
    fun `the register's own payloads are not indexed as documents`() {
        val payload = archive.archive(
            kind = DocumentKind("print"),
            externalId = "term10/print/424",
            title = "Rzadowy projekt ustawy",
            text = "{\"title\":\"projekt ustawy o mikroinstalacjach\",\"number\":\"424\"}",
        )

        indexer.indexDocumentText(payload)
        refresh()

        assertEquals(0, search.search(SearchQuery(text = "mikroinstalacjach")).total)
    }

    /** What a filed document is called is often only on the catalog page, so its address stands in. */
    @Test
    fun `a file with no title of its own is named by the address it is archived at`() {
        indexBill()

        val hit = search.search(SearchQuery(text = "mikroinstalacji")).hits.single()

        assertEquals("projekt/12409051/12345/katalog/1/dokument/9", hit.title)
    }

    private fun indexBill() = archive.archive(
        kind = FILED_DOCUMENT,
        externalId = "projekt/12409051/12345/katalog/1/dokument/9",
        title = null,
        text = "Projekt ustawy o zmianie ustawy o odnawialnych zrodlach energii. " +
            "Art. 1. Ustawa okresla zasady przylaczania fotowoltaicznych mikroinstalacji do sieci " +
            "dystrybucyjnej oraz obowiazki operatora wobec prosumenta.",
    ).also {
        indexer.indexDocumentText(it)
        refresh()
    }

    private fun refresh() = client.indices().refresh { it.index(LegislativeIndex.ALIAS) }

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
