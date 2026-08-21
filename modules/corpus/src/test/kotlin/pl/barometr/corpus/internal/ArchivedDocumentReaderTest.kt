package pl.barometr.corpus.internal

import org.junit.jupiter.api.Test
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.ingestion.api.ExternalId
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What each source's archived payload turns out to be.
 *
 * The fixtures are single entities lifted verbatim out of the connectors' recorded
 * responses, because that is exactly what the archive stores — one document per
 * entity. A reader that stops recognising them is a corpus quietly filling with
 * documents of unknown kind, which nothing downstream can match to an act.
 */
class ArchivedDocumentReaderTest {

    private val json = JsonMapper.builder().addModule(kotlinModule()).build()
    private val sejm = SejmArchivedDocumentReader(json)
    private val isap = IsapArchivedDocumentReader(json)
    private val rcl = RclArchivedDocumentReader()

    @Test
    fun `a Sejm print is titled and dated by the document it carries`() {
        val print = sejm.describe(ExternalId("term10/print/3005"), fixture("sejm/print.json"))

        assertEquals(DocumentKind("print"), print.kind)
        assertEquals(
            "Kandydat na stanowisko sędziego Trybunału Konstytucyjnego - Pan Artur Kotowski.",
            print.title,
        )
        // `documentDate`, the day the print itself is dated, not the day it reached us.
        assertEquals(Instant.parse("2026-08-12T00:00:00Z"), print.publishedAt)
    }

    @Test
    fun `each Sejm entity is titled by the field its own shape uses`() {
        val club = sejm.describe(ExternalId("term10/club/Centrum"), fixture("sejm/club.json"))
        val member = sejm.describe(ExternalId("term10/mp/1"), fixture("sejm/mp.json"))
        val voting = sejm.describe(ExternalId("term10/proceeding/1/voting/1"), fixture("sejm/voting.json"))
        val sitting = sejm.describe(ExternalId("term10/proceeding/1"), fixture("sejm/proceeding.json"))

        assertEquals(DocumentKind("club"), club.kind)
        assertEquals("Klub Parlamentarny Centrum", club.title)

        assertEquals(DocumentKind("mp"), member.kind)
        assertEquals("Andrzej Adamczyk", member.title)

        assertEquals(DocumentKind("voting"), voting.kind)
        assertEquals("wybór Marszałka Sejmu", voting.title)
        // A voting is stamped to the second, and the API states it in local time.
        assertEquals(Instant.parse("2023-11-13T15:17:22Z"), voting.publishedAt)

        assertEquals(DocumentKind("proceeding"), sitting.kind)
        // A sitting runs over several days; the first is when it began.
        assertEquals(Instant.parse("2023-11-13T00:00:00Z"), sitting.publishedAt)
    }

    @Test
    fun `an address no shape recognises is recorded as unknown rather than dropped`() {
        val odd = sejm.describe(ExternalId("term10/interpellation/7"), fixture("sejm/print.json"))

        assertEquals(DocumentKind.UNKNOWN, odd.kind)
        assertNull(odd.title)
    }

    @Test
    fun `an ISAP act is titled and dated by its publication in the journal`() {
        val act = isap.describe(ExternalId("DU/2026/1079"), fixture("isap/act.json"))

        assertEquals(DocumentKind("act"), act.kind)
        assertEquals("Ustawa z dnia 17 lipca 2026 r.", act.title?.take(30))
        // `promulgation`, not `announcementDate`: the day it appeared in Dziennik
        // Ustaw, which is the date this source orders itself by.
        assertEquals(Instant.parse("2026-08-10T00:00:00Z"), act.publishedAt)
    }

    @Test
    fun `an ISAP document not addressed by an ELI is unknown`() {
        val odd = isap.describe(ExternalId("DU-2026-1079"), fixture("isap/act.json"))

        assertEquals(DocumentKind.UNKNOWN, odd.kind)
    }

    /**
     * RPL pages are addressed, not read: the title lives behind the connector's
     * configured selectors, and a second parse of the same HTML here would put the
     * site's layout in two places.
     */
    @Test
    fun `RPL pages are classified by address alone`() {
        val page = { id: String -> rcl.describe(ExternalId(id), ByteArray(0)) }

        assertEquals(DocumentKind("rcl-project"), page("projekt/ustawa/12409051").kind)
        assertEquals(DocumentKind("rcl-change-register"), page("projekt/ustawa/12409051/rejestr").kind)
        assertEquals(DocumentKind("rcl-catalog"), page("projekt/ustawa/12409051/katalog/13196866").kind)
        assertEquals(
            DocumentKind("rcl-catalog-change-register"),
            page("projekt/ustawa/12409051/katalog/13196866/rejestr").kind,
        )
        assertNull(page("projekt/ustawa/12409051").title)
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
}
