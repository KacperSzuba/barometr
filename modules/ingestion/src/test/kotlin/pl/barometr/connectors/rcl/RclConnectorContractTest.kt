package pl.barometr.connectors.rcl

import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import pl.barometr.http.HttpFetch
import pl.barometr.http.HttpOutcome
import pl.barometr.http.RefusalReason
import pl.barometr.http.SourceHttpClient
import pl.barometr.ingestion.api.BackfillPartition
import pl.barometr.ingestion.api.Cursor
import pl.barometr.ingestion.api.SourceAccessDeniedException
import pl.barometr.ingestion.api.PayloadKind
import pl.barometr.ingestion.api.PayloadMediaTypes
import pl.barometr.ingestion.api.RawDocumentSink
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.api.SchemaWarning
import pl.barometr.ingestion.api.SinkOutcome
import pl.barometr.sources.api.IngestionMode
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The walk itself, driven by pages saved from the live site.
 *
 * Only three project cards were ever captured, so most of what this connector asks
 * for comes back 404 — and that is left as it is rather than papered over with
 * invented pages. It makes these tests an honest model of the real thing: RPL holds
 * twenty-four thousand drafts, some of them will fail to load, and a walk that
 * cannot survive that is worthless.
 */
class RclConnectorContractTest {

    // ——— Incremental ————————————————————————————————————————————————————————

    /**
     * A first pass establishes where "now" is; it does not read the archive. One
     * index page per kind, and history is left to backfill — otherwise the first
     * poll after a deployment would spend a fortnight crawling twenty-four thousand
     * drafts because a cursor happened to be empty.
     */
    @Test
    fun `a first pass takes one index page per kind rather than the archive`() {
        val site = FixtureSite()
        val sink = RecordingSink()

        val result = connectorOf(site).readChangesSince(cursor = null, sink = sink)

        // Ten bills, ten regulations, and the one draft a full tree was captured
        // for; the fourth kind has nothing listed.
        assertEquals(21, site.projectCardRequests)

        // Three of those are cards we hold, and the tree beneath one of them: two
        // change registers, the one catalog page a copy was saved of, and the twelve
        // files that page links.
        assertEquals(19, sink.accepted.size)
        assertTrue(sink.accepted.map { it.externalId.value }.containsAll(
            listOf("projekt/rozporzadzenia/12413554", "projekt/ustawy/12413553"),
        ))
        assertFalse(result.sourceUnchanged)
    }

    /**
     * The cursor is a date because RPL prints modification dates to the day, and
     * the comparison is inclusive for the same reason: a draft touched later on the
     * cursor's own day would otherwise never be seen again.
     */
    @Test
    fun `a cursor stops the walk at drafts already seen`() {
        val site = FixtureSite()

        connectorOf(site).readChangesSince(
            cursor = Cursor(IngestionMode.INCREMENTAL, mapOf(RclConnector.CURSOR_CHANGED_SINCE to "2026-08-13")),
            sink = RecordingSink(),
        )

        // Of the ten bills listed, five were modified on the 13th or later; of the
        // ten regulations, nine; plus the single-row index. Everything older is
        // left alone.
        assertEquals(15, site.projectCardRequests)
    }

    @Test
    fun `the cursor advances to the newest modification seen`() {
        val result = connectorOf(FixtureSite()).readChangesSince(cursor = null, sink = RecordingSink())

        assertEquals("2026-08-17", result.nextCursor?.get(RclConnector.CURSOR_CHANGED_SINCE))
    }

    @Test
    fun `a pass that reaches no drafts reports the source as unchanged`() {
        val site = FixtureSite()

        val result = connectorOf(site).readChangesSince(
            // Later than anything on the saved index pages.
            cursor = Cursor(IngestionMode.INCREMENTAL, mapOf(RclConnector.CURSOR_CHANGED_SINCE to "2027-01-01")),
            sink = RecordingSink(),
        )

        assertEquals(0, site.projectCardRequests)
        assertTrue(result.sourceUnchanged)
    }

    // ——— Surviving a walk that goes wrong ————————————————————————————————————

    /**
     * A draft that will not load is a hole in the archive with a cause worth
     * recording — not a reason to abandon the other twenty-three thousand. Without
     * this, one poison page would stop a replay from ever getting past it.
     */
    @Test
    fun `a draft that fails to load is recorded and the walk continues`() {
        val sink = RecordingSink()

        connectorOf(FixtureSite()).readChangesSince(cursor = null, sink = sink)

        assertTrue(sink.warnings.isNotEmpty())
        assertTrue(sink.warnings.any { it.path == "/projekt/12413507" })
        // Everything that does exist was still archived — including the catalog
        // tree of a draft reached after several failures in a row.
        assertEquals(19, sink.accepted.size)
    }

    /**
     * The failure mode this connector most needs to avoid. RPL's robots.txt
     * disallows everything, so if a refusal on the index were swallowed the run
     * would report success with zero documents — indistinguishable from a quiet
     * weekend, for as long as nobody thought to check.
     */
    @Test
    fun `a refused index fails the run instead of reporting a quiet weekend`() {
        val blocked = object : SourceHttpClient {
            override fun fetch(request: HttpFetch) =
                HttpOutcome.Refused(RefusalReason.ROBOTS_DISALLOWED, "Disallow: /")
        }

        assertFailsWith<SourceAccessDeniedException> {
            RclConnector(
                site = RclSiteClient(blocked),
                pages = RclUrls(BASE_URL),
                listings = RclListingParser(),
                cards = RclProjectCardParser(),
                registers = RclChangeRegisterParser(),
                catalogs = RclCatalogParser(),
            ).readChangesSince(cursor = null, sink = RecordingSink())
        }
    }

    // ——— The catalog tree ————————————————————————————————————————————————————

    /**
     * The walk has to reach two levels down or it archives an index of the content
     * instead of the content. A stage named "Konsultacje publiczne" holds five
     * catalogs of its own, and the comments submitted during consultation sit in
     * one of them.
     */
    @Test
    fun `the walk descends from a stage into the catalogs inside it`() {
        val sink = RecordingSink()

        connectorOf(FixtureSite()).readChangesSince(cursor = null, sink = sink)

        val archived = sink.accepted.map { it.externalId.value }
        // The stage register, and then a catalog discovered only by reading it.
        assertTrue(archived.contains("projekt/zalozenia/12409051/katalog/13196866/rejestr"))
        assertTrue(archived.contains("projekt/zalozenia/12409051/katalog/13196868/rejestr"))
    }

    /**
     * The depth is a setting because the level costs an order of magnitude, and
     * whether that is affordable depends on the pace RPL has agreed to rather than
     * on anything this code knows.
     */
    @Test
    fun `depth one stops at the stages without entering them`() {
        val sink = RecordingSink()

        connectorOf(FixtureSite(), catalogDepth = 1).readChangesSince(cursor = null, sink = sink)

        val archived = sink.accepted.map { it.externalId.value }
        assertTrue(archived.contains("projekt/zalozenia/12409051/katalog/13196866/rejestr"))
        assertFalse(archived.contains("projekt/zalozenia/12409051/katalog/13196868/rejestr"))
    }

    /**
     * Recursion driven by links scraped from a page should not be able to spin
     * forever if one of those links ever points back up. RPL has no reason to
     * produce one; the guard costs a set.
     */
    @Test
    fun `a catalog is never visited twice within one draft`() {
        val sink = RecordingSink()

        connectorOf(FixtureSite()).readChangesSince(cursor = null, sink = sink)

        val archived = sink.accepted.map { it.externalId.value }
        assertEquals(archived.size, archived.distinct().size)
    }
    // ——— The files filed under a stage ———————————————————————————————————————

    /**
     * The step the walk was missing. A change register names a filed document and
     * times it to the minute but carries no link to it, so before the catalog page
     * could be read the archive knew a document existed without knowing where to
     * fetch it.
     */
    @Test
    fun `the walk follows a catalog page to the files filed under it`() {
        val site = FixtureSite()
        val sink = RecordingSink()

        connectorOf(site).readChangesSince(cursor = null, sink = sink)

        assertEquals(12, site.filesRequested.size)
        val archived = sink.accepted.map { it.externalId.value }
        assertTrue(archived.contains("projekt/zalozenia/12409051/katalog/13196867/dokument/770754"))
        assertTrue(archived.contains("projekt/zalozenia/12409051/katalog/13196868/dokument/778141"))
    }

    /**
     * The format is taken from what the server served, and the archive records it —
     * so a Word document and a PDF filed in the same folder do not both arrive as
     * bytes of unknown kind.
     */
    @Test
    fun `a file is archived under the kind the server served it as`() {
        val sink = RecordingSink()

        connectorOf(FixtureSite()).readChangesSince(cursor = null, sink = sink)

        val byId = sink.accepted.associateBy { it.externalId.value }
        assertEquals(
            PayloadKind.DOCX,
            byId.getValue("projekt/zalozenia/12409051/katalog/13196867/dokument/770754").kind,
        )
        assertEquals(
            PayloadKind.PDF,
            byId.getValue("projekt/zalozenia/12409051/katalog/13196868/dokument/778141").kind,
        )

        // The one filing RPL named without an extension. Nothing but the media type
        // it was served under says what it is, which is the whole reason that answer
        // is preferred over the name.
        assertEquals(
            PayloadKind.DOCX,
            byId.getValue("projekt/zalozenia/12409051/katalog/13196870/dokument/792735").kind,
        )
    }

    /**
     * Where the two disagree the archive keeps the server's answer and says so. A
     * ministry filing a PDF under a `.docx` name is the shape change this exists to
     * surface, and picking either answer silently leaves something downstream
     * failing to parse with no idea why.
     */
    @Test
    fun `a file served as one format and named as another is recorded as a shape change`() {
        val sink = RecordingSink()
        val mislabelling = object : SourceHttpClient {
            private val real = FixtureSite()

            override fun fetch(request: HttpFetch): HttpOutcome {
                val outcome = real.fetch(request)
                if (!request.url.path.startsWith("/docs/")) return outcome
                // Same bytes, a media type that contradicts the link's extension.
                val served = outcome as HttpOutcome.Fetched
                return HttpOutcome.Fetched(served.body, "application/pdf", null, null)
            }
        }

        connectorOf(mislabelling).readChangesSince(cursor = null, sink = sink)

        val mismatched = sink.warnings.filter { it.kind == SchemaWarning.Kind.UNEXPECTED_TYPE }
        // Eight of the twelve carry a .docx name, three a .pdf one, and the last
        // carries no extension at all — so only the eight can contradict a PDF.
        assertEquals(8, mismatched.size)
        assertEquals(12, sink.accepted.count { it.kind == PayloadKind.PDF })
    }

    /**
     * The expensive half of the walk, and switchable for that reason: a stage holds
     * a dozen files where it holds one page, and how much of that RPL will tolerate
     * is a question about an agreed pace rather than one this code can answer.
     */
    @Test
    fun `turning the files off leaves the pages and fetches nothing else`() {
        val site = FixtureSite()
        val sink = RecordingSink()

        connectorOf(site, fetchAttachments = false).readChangesSince(cursor = null, sink = sink)

        assertEquals(0, site.filesRequested.size)
        assertEquals(7, sink.accepted.size)
        assertTrue(
            sink.accepted.map { it.externalId.value }
                .contains("projekt/zalozenia/12409051/katalog/13196866"),
        )
    }

    /**
     * A catalog page renders its whole subtree, so a file appears again on every
     * page above the one it is filed in. The sink would recognise the repeat and
     * store nothing; the point of the guard is the request it saves, which at one
     * every five seconds is worth more than the write.
     */
    @Test
    fun `a file is fetched once however many pages list it`() {
        val walk = RclDraftWalk(RclProjectType.BILLS, "12409051", RecordingSink())

        assertTrue(walk.fetchesDocument("770751"))
        assertFalse(walk.fetchesDocument("770751"))
        assertTrue(walk.fetchesDocument("770752"))
    }


    // ——— Backfill ———————————————————————————————————————————————————————————

    @Test
    fun `partitions cover every kind of draft`() {
        val partitions = connectorOf(FixtureSite())
            .partitions(LocalDate.of(2020, 1, 1), LocalDate.of(2026, 12, 31))

        assertEquals(
            listOf("zalozenia", "ustawy", "rozporzadzenia", "osr"),
            partitions.map { it.key },
        )
    }

    @Test
    fun `an interrupted replay resumes at the next index page`() {
        val site = FixtureSite()
        val connector = connectorOf(site)
        val partition = BackfillPartition("ustawy", "Projekty ustaw")

        val first = connector.readPartitionChunk(partition, cursor = null, sink = RecordingSink())
        assertEquals("1", first.nextCursor?.get(RclConnector.CURSOR_LAST_PAGE))
        assertFalse(first.exhausted)

        val second = connector.readPartitionChunk(partition, cursor = first.nextCursor, sink = RecordingSink())
        assertEquals("2", second.nextCursor?.get(RclConnector.CURSOR_LAST_PAGE))
        assertEquals(2, site.indexPagesRequested.filter { it.contains("typeId=2") }.size)
    }

    @Test
    fun `a partition read to its last page is marked exhausted`() {
        // A page large enough to hold all 2602 bills, so one page is the whole kind.
        val connector = connectorOf(FixtureSite(), pageSize = 10_000)

        val result = connector.readPartitionChunk(
            BackfillPartition("ustawy", "Projekty ustaw"),
            cursor = null,
            sink = RecordingSink(),
        )

        assertTrue(result.exhausted)
        assertEquals("true", result.nextCursor?.get(Cursor.PARTITION_DONE))
    }

    // ——— Completeness ————————————————————————————————————————————————————————

    /**
     * The count RPL prints in its own header, which it derives from a query rather
     * than from the rows it sent us — so, like the Sejm's print count, it can
     * genuinely prove a replay lost nothing.
     */
    @Test
    fun `the declared volume is the count the source states for itself`() {
        val volumes = connectorOf(FixtureSite())
            .declaredVolumes(BackfillPartition("rozporzadzenia", "Projekty rozporządzeń"))

        val declared = volumes.single()
        assertEquals(22121, declared.declaredCount)
        assertEquals("projekt/rozporzadzenia/", declared.externalIdPrefix)
        assertTrue(declared.isAuthoritative)
    }

    /**
     * The prefix has to pair with the archive's "directly under" count, or the
     * registers and catalogs nested beneath a card would be counted as cards and
     * the audit would report an archive several times more complete than it is.
     */
    @Test
    fun `nested pages sit below the counting prefix rather than inside it`() {
        val prefix = RclExternalIds.projectPrefix(RclProjectType.BILLS)
        val card = RclExternalIds.project(RclProjectType.BILLS, "12409051").value
        val register = RclExternalIds.projectChangeRegister(RclProjectType.BILLS, "12409051").value
        val catalog = RclExternalIds.catalog(RclProjectType.BILLS, "12409051", "13196866").value

        assertEquals("projekt/ustawy/12409051", card)
        assertFalse(card.removePrefix(prefix).contains('/'))
        assertTrue(register.removePrefix(prefix).contains('/'))
        assertTrue(catalog.removePrefix(prefix).contains('/'))
    }

    // ——— Fixtures ————————————————————————————————————————————————————————————

    private fun connectorOf(
        site: SourceHttpClient,
        pageSize: Int = 100,
        catalogDepth: Int = RclWalkSettings.DEFAULT_CATALOG_DEPTH,
        fetchAttachments: Boolean = true,
    ) = RclConnector(
        site = RclSiteClient(site),
        pages = RclUrls(BASE_URL),
        listings = RclListingParser(),
        cards = RclProjectCardParser(),
        registers = RclChangeRegisterParser(),
        catalogs = RclCatalogParser(),
        settings = RclWalkSettings(
            pageSize = pageSize,
            catalogDepth = catalogDepth,
            fetchAttachments = fetchAttachments,
        ),
    )

    /**
     * Serves the saved pages, and 404s everything else.
     *
     * The two kinds of draft nobody saved an index for are served an *emptied* copy
     * of a real index rather than an invented one — same markup, no rows, count set
     * to zero — so this class never asserts anything about a structure that was not
     * observed.
     */
    private class FixtureSite : SourceHttpClient {

        val indexPagesRequested = mutableListOf<String>()
        val filesRequested = mutableListOf<String>()
        var projectCardRequests = 0
            private set

        override fun fetch(request: HttpFetch): HttpOutcome {
            val path = request.url.path
            val query = request.url.query.orEmpty()

            if (path == "/lista") {
                indexPagesRequested += query
                return when (typeIdIn(query)) {
                    RclProjectType.BILLS.typeId -> serve("list-ustawy.html")
                    RclProjectType.REGULATIONS.typeId -> serve("list-rozporzadzenia.html")
                    // The one draft a full catalog tree was captured for. Which kind
                    // it is listed under is incidental to the walk being tested.
                    RclProjectType.ASSUMPTIONS.typeId ->
                        HttpOutcome.Fetched(indexOf(TREE_PROJECT), "text/html", null, null)
                    else -> HttpOutcome.Fetched(indexOf(null), "text/html", null, null)
                }
            }

            // Files come back as opaque bytes under the media type RPL states for
            // them. What is in them is not this suite's subject — the walk is — and
            // a dozen real Word documents in the repository would prove nothing that
            // a dozen bytes do not.
            if (path.startsWith("/docs/")) {
                filesRequested += path
                return HttpOutcome.Fetched(path.toByteArray(), mediaTypeOf(path), null, null)
            }

            if (PROJECT_CARD.matches(path)) projectCardRequests++

            return FIXTURES_BY_PATH[path]?.let { serve(it) }
                ?: HttpOutcome.Failed(404, "no saved page for $path")
        }

        private fun typeIdIn(query: String): Int? = query.split('&')
            .firstOrNull { it.startsWith("typeId=") }
            ?.removePrefix("typeId=")
            ?.toIntOrNull()

        private fun mediaTypeOf(path: String): String =
            PayloadMediaTypes.of(PayloadKind.of(path.substringAfterLast('.')) ?: PayloadKind.BINARY)

        private fun serve(name: String) =
            HttpOutcome.Fetched(bytesOf(name), "text/html; charset=utf-8", null, null)

        /**
         * A real index page cut down to at most one row.
         *
         * Derived from saved markup rather than written: the structure is RPL's, and
         * only which draft the row points at is ours. Everything this test suite
         * asserts about index *structure* is checked elsewhere against untouched
         * pages, so the edit here cannot make a broken selector look fine.
         */
        private fun indexOf(projectId: String?): ByteArray {
            val page = Jsoup.parse(String(bytesOf("list-ustawy.html"), Charsets.UTF_8))
            val rows = page.select("#lista table#table > tbody > tr")
            rows.drop(if (projectId == null) 0 else 1).forEach { it.remove() }
            projectId?.let {
                rows.first()!!.selectFirst("td:eq(0) a")!!.attr("href", "/projekt/$it")
            }
            page.selectFirst("#list .col-sm-8")
                ?.text("Lista projektów według wybranych kryteriów: ${if (projectId == null) 0 else 1}")
            return page.outerHtml().toByteArray(Charsets.UTF_8)
        }

        private fun bytesOf(name: String): ByteArray =
            checkNotNull(javaClass.getResourceAsStream("/fixtures/rcl/$name")) {
                "Missing fixture $name"
            }.use { it.readBytes() }

        private companion object {
            const val TREE_PROJECT = "12409051"
            val PROJECT_CARD = Regex("""^/projekt/\d+$""")
            val FIXTURES_BY_PATH = mapOf(
                "/projekt/12413553" to "project-ustawa-12413553.html",
                "/projekt/12413554" to "project-rozporzadzenie-12413554.html",
                "/projekt/12409051" to "project-ustawa-12409051.html",
                "/projekt/12409051/katalog/13196866" to "catalog-13196866-konsultacje.html",
                "/projekt/rejestr/projekt/12409051" to "register-project-12409051.html",
                "/projekt/rejestr/katalog/13196859" to "register-catalog-13196859.html",
                "/projekt/rejestr/katalog/13196866" to "register-catalog-13196866-konsultacje.html",
                "/projekt/rejestr/katalog/13196868" to "register-catalog-13196868-pisma.html",
            )
        }
    }

    private class RecordingSink : RawDocumentSink {
        val accepted = mutableListOf<RawPayload>()
        val warnings = mutableListOf<SchemaWarning>()

        override fun archive(payload: RawPayload): SinkOutcome {
            accepted += payload
            return SinkOutcome.STORED
        }

        override fun recordSchemaWarning(warning: SchemaWarning) {
            warnings += warning
        }
    }

    private companion object {
        val BASE_URL: URI = URI.create("https://legislacja.rcl.gov.pl")
    }
}
