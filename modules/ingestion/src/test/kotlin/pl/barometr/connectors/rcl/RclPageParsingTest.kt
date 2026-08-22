package pl.barometr.connectors.rcl

import pl.barometr.connectors.rcl.api.RclStageState
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract test against pages saved from the live RPL site.
 *
 * Saved rather than handwritten, for the reason that decides whether this kind of
 * test is worth anything: a stub built from what we believe the markup looks like
 * only ever confirms our belief. RPL publishes no API and no schema, so the pages
 * *are* the contract — refreshing these fixtures and watching this test go red is
 * the only warning we will get before a layout change turns into missing data.
 */
class RclPageParsingTest {

    private val listings = RclListingParser()
    private val cards = RclProjectCardParser()
    private val registers = RclChangeRegisterParser()
    private val catalogs = RclCatalogParser()

    // ——— Listings ————————————————————————————————————————————————————————————

    @Test
    fun `reads a bill listing with its site-wide total`() {
        val page = listings.readListing(fixture("list-ustawy.html"))

        // The site's own tally, not the ten rows on this page — it is what tells a
        // walk how far it has to go before it takes its first step.
        assertEquals(2602, page.totalCount)
        assertEquals(10, page.entries.size)

        val first = page.entries.first()
        assertEquals("12413553", first.projectId)
        assertTrue(first.title.startsWith("Projekt ustawy o zmianie ustawy o ochronie informacji"))
        assertEquals("Minister-Członek Rady Ministrów Koordynator Służb Specjalnych", first.applicant)
        assertEquals("UD412", first.registerNumber)
        assertEquals(LocalDate.of(2026, 8, 17), first.createdAt)
        assertEquals(LocalDate.of(2026, 8, 17), first.modifiedAt)
    }

    /**
     * Regression guard for the largest collection on the site: RPL renders a nested
     * `<html>` document reading "Strona nie istnieje." where the regulations pager
     * should be. Anything deriving a page count from those links breaks on 22 121
     * records while working fine on 2 602, so the total is read from the header.
     */
    @Test
    fun `reads the regulations listing despite the broken pager`() {
        val page = listings.readListing(fixture("list-rozporzadzenia.html"))

        assertEquals(22121, page.totalCount)
        assertEquals(10, page.entries.size)
        assertEquals(222, page.pageCount(pageSize = 100))
        assertEquals("12413554", page.entries.first().projectId)
    }

    @Test
    fun `a modification date differing from creation survives the round trip`() {
        val page = listings.readListing(fixture("list-ustawy.html"))

        // The draft that makes incremental ingestion worth doing at all: filed on
        // the 5th, touched on the 17th. Ordered by creation it sits tenth; ordered
        // by modification it sits near the top, which is why the incremental walk
        // sorts the other way.
        val amended = page.entries.single { it.projectId == "12413201" }
        assertEquals(LocalDate.of(2026, 8, 5), amended.createdAt)
        assertEquals(LocalDate.of(2026, 8, 17), amended.modifiedAt)
    }

    // ——— Project cards ———————————————————————————————————————————————————————

    @Test
    fun `reads a bill card with its metadata and stages`() {
        val card = assertNotNull(cards.readProjectCard(fixture("project-ustawa-12409051.html")))

        assertEquals("12409051", card.projectId)
        assertTrue(card.title.startsWith("Projekt ustawy o zmianie ustawy – Kodeks postępowania cywilnego"))
        assertEquals("Minister Sprawiedliwości", card.applicant)
        assertEquals("UD383", card.registerNumber)
        assertEquals("otwarty", card.status)
        assertEquals("X", card.term)
        assertEquals(listOf("sprawiedliwość"), card.departments)
        assertEquals(
            listOf("POŻYCZKI", "KREDYTY", "UBEZPIECZENIA MAJĄTKOWE I OSOBOWE"),
            card.keywords,
        )
        assertEquals(10, card.stages.size)
    }

    /**
     * The cross-link out of RCL and into the government's programme of work — the
     * join that lets a draft here be matched to its `UD`/`RD` entry on gov.pl.
     */
    /**
     * The three fields anything deriving from a card actually needs, and the two that
     * had to be typed for it: RPL prints the term in roman numerals and the creation
     * date day-first, and neither is how the rest of the system counts or parses.
     */
    @Test
    fun `a card states when the draft began, in which term, and under which number`() {
        val card = assertNotNull(cards.readProjectCard(fixture("project-ustawa-12409051.html")))

        assertEquals(LocalDate.parse("2026-04-09"), card.createdOn)
        assertEquals(10, card.termNumber)
        assertEquals("UD383", card.registerNumber)
    }

    /**
     * The port the other contexts read archived pages through — the same parser and
     * the same selectors, from bytes rather than from a parsed document.
     */
    @Test
    fun `the published reader sees exactly what the parser sees`() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/fixtures/rcl/project-ustawa-12409051.html"))
            .use { it.readBytes() }

        val throughPort = assertNotNull(JsoupRclPageReader(cards, catalogs).readProjectCard(bytes))

        assertEquals(cards.readProjectCard(fixture("project-ustawa-12409051.html"))?.title, throughPort.title)
        assertEquals("12409051", throughPort.projectId)
        assertTrue(throughPort.title.isNotBlank())
    }

    @Test
    fun `captures the deep link to the programme of work`() {
        val card = assertNotNull(cards.readProjectCard(fixture("project-ustawa-12409051.html")))

        assertEquals(
            "https://www.gov.pl/web/premier/projekt-ustawy-o-zmianie-ustawy--kodeks-postepowania-cywilnego-oraz-ustawy-o-dzialalnosci-ubezpieczeniowej-i-reasekuracyjnej",
            card.programmeOfWorkUrl,
        )
    }

    @Test
    fun `tells the three stage states apart`() {
        val card = assertNotNull(cards.readProjectCard(fixture("project-ustawa-12409051.html")))
        val byOrdinal = card.stages.associateBy { it.ordinal }

        assertEquals(RclStageState.NOT_STARTED, byOrdinal[1]?.state)
        assertEquals(RclStageState.DONE, byOrdinal[2]?.state)
        assertEquals(RclStageState.DONE, byOrdinal[3]?.state)
        assertEquals(RclStageState.CURRENT, byOrdinal[4]?.state)
        assertEquals(RclStageState.NOT_STARTED, byOrdinal[5]?.state)

        assertEquals("Uzgodnienia", byOrdinal[2]?.name)
        assertEquals("Konsultacje publiczne", byOrdinal[3]?.name)
        assertEquals(LocalDate.of(2026, 5, 26), byOrdinal[3]?.lastModifiedAt)
    }

    /**
     * The reason stages are not modelled as a pipeline. On this regulation
     * "Konsultacje publiczne" never started while "Opiniowanie", two places later,
     * is under way — a stage skipped outright with a later one running. A state
     * machine that insisted on order would have to call this card corrupt.
     */
    @Test
    fun `a skipped stage is represented faithfully rather than reordered`() {
        val card = assertNotNull(cards.readProjectCard(fixture("project-rozporzadzenie-12413554.html")))
        val byOrdinal = card.stages.associateBy { it.ordinal }

        assertEquals(RclStageState.DONE, byOrdinal[2]?.state)
        assertEquals(RclStageState.NOT_STARTED, byOrdinal[3]?.state)
        assertEquals(RclStageState.CURRENT, byOrdinal[4]?.state)
    }

    /**
     * Stage counts and names are per-draft, not a fixed vocabulary: a regulation
     * ends at publication in the Journal of Laws, a bill at referral to the Sejm.
     * Any enum of stages would have been wrong on the second card it met.
     */
    @Test
    fun `stage vocabulary differs between drafts`() {
        val regulation = assertNotNull(cards.readProjectCard(fixture("project-rozporzadzenie-12413554.html")))
        val bill = assertNotNull(cards.readProjectCard(fixture("project-ustawa-12413553.html")))

        assertEquals(12, regulation.stages.size)
        assertEquals(14, bill.stages.size)
        assertEquals("Skierowanie aktu do ogłoszenia", regulation.stages.last().name)
        assertEquals("Skierowanie projektu ustawy do Sejmu", bill.stages.last().name)
    }

    /**
     * Every stage owns a catalog from the moment the draft is filed, so an id alone
     * proves nothing about content. Whether RPL links the stage is the signal that
     * there is something behind it worth fetching.
     */
    @Test
    fun `only linked stages are worth visiting`() {
        val card = assertNotNull(cards.readProjectCard(fixture("project-ustawa-12409051.html")))

        assertTrue(card.stages.all { it.catalogId.isNotBlank() })
        assertEquals(listOf(2, 3, 4), card.visitableStages.map { it.ordinal })
        assertEquals("13196866", card.visitableStages.single { it.ordinal == 3 }.catalogId)
    }

    // ——— Change registers ————————————————————————————————————————————————————

    @Test
    fun `reads a project change register`() {
        val register = registers.readChangeRegister(fixture("register-project-12409051.html"))

        assertEquals(41, register.changes.size)

        val created = register.changes.first()
        assertEquals(RclChangeKind.PROJECT_CREATED, created.kind)
        assertEquals(LocalDateTime.of(2026, 4, 9, 15, 14), created.occurredAt)
        assertEquals("Aneta Sobolewska", created.author)
    }

    /**
     * Why the registers are fetched at all. A card says a stage was last touched on
     * some date; the register says the draft entered "Konsultacje publiczne" at
     * 15:26 that day. `valid_from` in a bitemporal record wants the second one.
     */
    @Test
    fun `stage transitions carry a timestamp accurate to the minute`() {
        val register = registers.readChangeRegister(fixture("register-project-12409051.html"))

        val transitions = register.stageTransitions
        assertEquals(3, transitions.size)
        assertEquals(
            listOf("2. Uzgodnienia", "3. Konsultacje publiczne", "4. Opiniowanie"),
            transitions.map { it.newValue },
        )
        assertEquals(LocalDateTime.of(2026, 4, 9, 15, 26), transitions[1].occurredAt)
    }

    @Test
    fun `an attribute change is split into attribute and new value`() {
        val register = registers.readChangeRegister(fixture("register-project-12409051.html"))

        val number = register.changes.single {
            it.attribute == "numer z wykazu prac legislacyjnych"
        }
        assertEquals(RclChangeKind.ATTRIBUTE_CHANGED, number.kind)
        assertEquals("UD383", number.newValue)

        // An attribute cleared to nothing must survive as an empty value rather
        // than failing to match: RPL logs plenty of these.
        val cleared = register.changes.single { it.attribute == "ustawy/uchwały nowelizowane" }
        assertEquals("", cleared.newValue)
    }

    /**
     * The "(rejestr)" anchor shares its cell with the wording. Left in, it would
     * append itself to every description and stop the wording patterns matching —
     * so it is taken as an id first and then removed.
     */
    @Test
    fun `a catalog link is taken as an id and kept out of the description`() {
        val register = registers.readChangeRegister(fixture("register-project-12409051.html"))

        val added = register.changes.first { it.kind == RclChangeKind.CATALOG_ADDED }
        assertEquals("13196859", added.catalogId)
        assertEquals("Dodano katalog 1. Zgłoszenia lobbingowe", added.description)
        assertTrue(register.changes.none { it.description.contains("rejestr") })
    }

    @Test
    fun `reads a catalog change register`() {
        val register = registers.readChangeRegister(fixture("register-catalog-13196859.html"))

        assertEquals(2, register.changes.size)
        assertEquals(RclChangeKind.CATALOG_CREATED, register.changes.first().kind)
        assertEquals("zgloszenia", register.changes.last().newValue)
    }

    /**
     * Not a formatting detail. RPL publishes the names of the officials who edit
     * each draft, and one register entry carries a work e-mail address in its
     * value. It reaches the archive because dropping it in the parser would break
     * provenance silently — but it is personal data, and what the system retains
     * belongs with this source's recorded legal basis.
     */
    @Test
    fun `the register carries personal data through to the caller`() {
        val register = registers.readChangeRegister(fixture("register-project-12409051.html"))

        assertContains(register.changes.map { it.author }, "Aneta Sobolewska")
        assertEquals(
            "aneta.sobolewska@ms.gov.pl",
            register.changes.single { it.attribute == "email założyciela" }.newValue,
        )
    }


    // ——— The catalog tree ————————————————————————————————————————————————————

    /**
     * The discovery that changes the shape of the walk: a stage does not hold
     * documents. "Konsultacje publiczne" holds five catalogs of its own, and the
     * submitted comments and the ministry's answer to them are two of those five.
     * A walk that stopped at the stage would archive an index and miss the content.
     */
    @Test
    fun `a stage catalog turns out to hold catalogs of its own`() {
        val register = registers.readChangeRegister(fixture("register-catalog-13196866-konsultacje.html"))

        assertEquals(
            listOf(
                "13196867" to "a) Projekt",
                "13196868" to "b) Pisma kierujące projekt do konsultacji publicznych",
                "13196869" to "c) Stanowiska zgłoszone w ramach konsultacji publicznych",
                "13196870" to "d) Odniesienie się wnioskodawcy do uwag",
                "13196871" to "e) Odrębna konferencja z udziałem podmiotów publicznych",
            ),
            register.childCatalogs.map { it.catalogId to it.name },
        )
    }

    /**
     * The heading is the only place RPL states where a register sits in the tree,
     * and it distinguishes the two levels in prose: a stage register says
     * `w projekcie`, a sub-catalog register says `w katalogu`.
     */
    @Test
    fun `a register states what it hangs beneath`() {
        val stage = registers.readChangeRegister(fixture("register-catalog-13196866-konsultacje.html"))
        val child = registers.readChangeRegister(fixture("register-catalog-13196868-pisma.html"))
        val project = registers.readChangeRegister(fixture("register-project-12409051.html"))

        val stageSubject = assertNotNull(stage.subject)
        assertEquals(RclRegisterScope.CATALOG, stageSubject.scope)
        assertEquals("Konsultacje publiczne", stageSubject.name)
        assertEquals(RclRegisterScope.PROJECT, stageSubject.parentScope)
        assertEquals("12409051", stageSubject.parentId)

        val childSubject = assertNotNull(child.subject)
        assertEquals("Pisma kierujące projekt do konsultacji publicznych", childSubject.name)
        assertEquals(RclRegisterScope.CATALOG, childSubject.parentScope)
        assertEquals("Konsultacje publiczne", childSubject.parentName)
        assertEquals("13196866", childSubject.parentId)

        val projectSubject = assertNotNull(project.subject)
        assertEquals(RclRegisterScope.PROJECT, projectSubject.scope)
        assertTrue(projectSubject.name.startsWith("Projekt ustawy o zmianie ustawy – Kodeks"))
    }

    /**
     * The heading is a bare text node sharing a block with the rows, and the rows
     * link to registers too. Picking the wrong link would make every sub-catalog
     * claim the wrong parent and quietly corrupt the tree.
     */
    @Test
    fun `the parent link is not confused with the row links below it`() {
        val register = registers.readChangeRegister(fixture("register-catalog-13196866-konsultacje.html"))

        // 13196867 is the first child listed in the rows; the parent is the project.
        assertEquals("12409051", register.subject?.parentId)
    }

    /**
     * A filing, timed to the minute, attributed to the ministry that made it. This
     * is the event the alerting side of the system ultimately fires on.
     */
    @Test
    fun `a filed document is recorded with its name and the minute it arrived`() {
        val register = registers.readChangeRegister(fixture("register-catalog-13196868-pisma.html"))

        val filed = register.documentsFiled.single()
        assertEquals("1e pismo konsultacje publiczne rozdzielnik.pdf", filed.documentName)
        assertEquals(LocalDateTime.of(2026, 5, 26, 14, 46), filed.occurredAt)
        assertEquals("Minister Sprawiedliwości", filed.author)
    }

    /**
     * The register names the file but does not link it, so this page says a document
     * exists without saying where to fetch it. The catalog page is what closes that
     * gap, and the pair of these tests is the whole point: two pages that each hold
     * half of what a filing is.
     */
    @Test
    fun `a filed document carries no link to itself in the register`() {
        val register = registers.readChangeRegister(fixture("register-catalog-13196868-pisma.html"))

        assertNull(register.documentsFiled.single().catalogId)
    }

    // ——— The catalog page ————————————————————————————————————————————————————

    /**
     * The step the walk was missing, now readable: the same filing the register
     * could only name is linked here, under the folder it was filed in.
     */
    @Test
    fun `a catalog links every file filed anywhere beneath it`() {
        val catalog = catalogs.readCatalog(fixture("catalog-13196866-konsultacje.html"))

        assertEquals(12, catalog.documents.size)
        val letter = catalog.documents.single { it.documentId == "778141" }
        assertEquals("1e pismo konsultacje publiczne rozdzielnik.pdf", letter.fileName)
        assertEquals("/docs//1/12409051/13196866/13196868/dokument778141.pdf", letter.href)
        assertEquals(LocalDate.of(2026, 5, 26), letter.createdOn)
        assertEquals("Minister Sprawiedliwości", letter.author)
    }

    /**
     * A file belongs to the folder its own href names, not to the page it was read
     * from. Every file here was read from catalog 13196866 and none of them is filed
     * in it.
     */
    @Test
    fun `a file is filed in the folder its href names, not the page it was read from`() {
        val catalog = catalogs.readCatalog(fixture("catalog-13196866-konsultacje.html"))

        assertEquals(
            setOf("13196867", "13196868", "13196869", "13196870"),
            catalog.documents.map { it.catalogId }.toSet(),
        )
    }

    /**
     * The folders a stage is divided into, including the one nothing has been filed
     * in yet — an empty folder is a fact about the process, not a row to drop.
     */
    @Test
    fun `a catalog names the folders inside it`() {
        val catalog = catalogs.readCatalog(fixture("catalog-13196866-konsultacje.html"))

        assertEquals(
            listOf(
                "Projekt",
                "Pisma kierujące projekt do konsultacji publicznych",
                "Stanowiska zgłoszone w ramach konsultacji publicznych",
                "Odniesienie się wnioskodawcy do uwag",
                "Odrębna konferencja z udziałem podmiotów publicznych",
            ),
            catalog.childDirectories.map { it.name },
        )
        assertEquals("13196871", catalog.childDirectories.last().catalogId)
        assertTrue(catalog.documents.none { it.catalogId == "13196871" })
    }

    /**
     * The document this whole tranche exists for. "Kto to wykreślił" is answered from
     * a table of comments and the applicant's reply to it, and both are filed here.
     */
    @Test
    fun `the tables of comments from consultation are among the files linked`() {
        val catalog = catalogs.readCatalog(fixture("catalog-13196866-konsultacje.html"))

        assertTrue(catalog.documents.any { it.fileName == "tabela uwag UD383.docx" })
        assertTrue(
            catalog.documents.any {
                it.fileName == "odpowiedź wnioskodawcy - tabela uwag - konsultacje publiczne"
            },
        )
    }

    /**
     * RPL indents these lines with `&nbsp;`, which no `trim` removes. Left in, every
     * author would carry three invisible characters and no two readings of the same
     * ministry would compare equal.
     */
    @Test
    fun `the non-breaking spaces RPL indents with are not part of a name`() {
        val catalog = catalogs.readCatalog(fixture("catalog-13196866-konsultacje.html"))

        assertTrue(catalog.documents.none { it.fileName.contains(NON_BREAKING_SPACE) })
        assertTrue(catalog.documents.mapNotNull { it.author }.none { it.contains(NON_BREAKING_SPACE) })
        assertTrue(catalog.childDirectories.none { it.name.contains(NON_BREAKING_SPACE) })
    }

    /**
     * A page that is not a catalog is an empty catalog, not a failure: a stage
     * nothing has been filed under renders exactly like a folder with nothing in it,
     * and the two are not worth telling apart.
     */
    @Test
    fun `a page that holds no filings reads as an empty catalog`() {
        val catalog = catalogs.readCatalog(fixture("home.html"))

        assertTrue(catalog.documents.isEmpty())
        assertTrue(catalog.childDirectories.isEmpty())
    }

    // ——— Selector configuration ——————————————————————————————————————————————

    /**
     * Every group is written now, catalog included. This was the test that recorded
     * the gap; it records the closing of it, and it will fail again the day a YAML
     * override blanks one of the fields.
     */
    @Test
    fun `every selector group is written against a captured page`() {
        val selectors = RclSelectors()

        assertTrue(selectors.canWalkSite)
        assertTrue(selectors.isConfigured)
        assertEquals(emptyList(), selectors.missingFields())
    }

    private fun fixture(name: String): Document {
        val html = checkNotNull(javaClass.getResourceAsStream("/fixtures/rcl/$name")) {
            "Missing fixture $name"
        }.use { it.readBytes().toString(Charsets.UTF_8) }
        return Jsoup.parse(html, "https://legislacja.rcl.gov.pl/")
    }

    private companion object {
        /** What RPL indents its detail lines with, and what no trim removes. */
        const val NON_BREAKING_SPACE = '\u00A0'
    }
}
