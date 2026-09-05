package pl.barometr.legislative.internal

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.legislative.internal.jooq.tables.references.ACT
import pl.barometr.legislative.internal.jooq.tables.references.ACT_IDENTIFIER
import pl.barometr.legislative.internal.jooq.tables.references.ACT_REFERENCE
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.shared.Eli
import pl.barometr.shared.Ids
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The card a reader lands on from a search hit, an alert or the draft they followed.
 *
 * Most of what it answers is the change graph, and the graph is the reason this is
 * tested against the schema rather than in isolation: both directions of it are a join
 * to acts this archive may or may not hold, and "may not" is the ordinary case.
 */
class ActCardsTest {

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()

    private val acts = ActRepository(dsl, clock)
    private val identifiers = ActIdentifierRepository(dsl, clock)
    private val references = ActReferenceRepository(dsl, clock)
    private val drafts = DraftRepository(dsl, clock)
    private val diffs = FakeDiffs()
    private val cards = ActCards(LegislativeCatalogAdapter(dsl), references, identifiers, drafts, acts, diffs)

    private val statedBy = DocumentVersionId(Ids.next())

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(ACT_REFERENCE).execute()
        dsl.deleteFrom(ACT_IDENTIFIER).execute()
        dsl.deleteFrom(DRAFT).execute()
        dsl.deleteFrom(ACT).execute()
    }

    @Test
    fun `an act is answered by its identifier and by its address alike`() {
        val id = record(BUILDING_LAW, "Prawo budowlane")

        val byId = cards.cardFor(id)
        val byAddress = cards.cardFor(BUILDING_LAW)

        assertEquals(byId, byAddress)
        assertEquals("Prawo budowlane", byId.act.title)
    }

    /**
     * The most consequential number on the card, and it is arithmetic on two dates the
     * journal states — never an estimate.
     */
    @Test
    fun `vacatio legis is the days between announcement and application`() {
        val id = record(
            BUILDING_LAW,
            "Prawo budowlane",
            announcedOn = LocalDate.of(2024, 8, 1),
            appliesFrom = LocalDate.of(2024, 9, 15),
        )

        assertEquals(45, cards.cardFor(id).vacatioLegisDays)
    }

    /** An act that applies the day it is announced has a real answer of zero. */
    @Test
    fun `no vacatio legis is zero, and an unknown date is not zero`() {
        val sameDay = record(
            BUILDING_LAW,
            "Prawo budowlane",
            announcedOn = LocalDate.of(2024, 8, 1),
            appliesFrom = LocalDate.of(2024, 8, 1),
        )
        val undated = record(Eli("DU/2024/17"), "Ustawa bez dat", announcedOn = null, appliesFrom = null)

        assertEquals(0, cards.cardFor(sameDay).vacatioLegisDays)
        assertNull(cards.cardFor(undated).vacatioLegisDays)
    }

    @Test
    fun `the card shows what the act changed and what has changed it`() {
        val id = record(BUILDING_LAW, "Prawo budowlane")
        record(AMENDMENT, "Ustawa o zmianie Prawa budowlanego", announcedOn = LocalDate.of(2025, 3, 1))

        references.replaceReferencesFrom(
            AMENDMENT,
            listOf(ActReferenceEdge(AMENDMENT, BUILDING_LAW, ActRelation.AMENDS)),
            statedBy,
        )

        val card = cards.cardFor(id)

        assertTrue(card.changes.isEmpty(), "this act changed nothing")
        val incoming = card.changedBy.single()
        assertEquals(AMENDMENT, incoming.eli)
        assertEquals(ActRelation.AMENDS, incoming.relation)
        assertEquals("Ustawa o zmianie Prawa budowlanego", incoming.title)
    }

    /**
     * The ordinary case: a 2026 act names a statute from a decade this ingestion never
     * reached. The citation is still worth showing — the address is what a reader
     * recognises — so it comes back with no title rather than not at all.
     */
    @Test
    fun `an act cites one this archive does not hold, and the citation survives`() {
        val id = record(AMENDMENT, "Ustawa o zmianie Prawa budowlanego")
        val neverIngested = Eli("DU/1997/604")

        references.replaceReferencesFrom(
            AMENDMENT,
            listOf(ActReferenceEdge(AMENDMENT, neverIngested, ActRelation.AMENDS)),
            statedBy,
        )

        val citation = cards.cardFor(id).changes.single()

        assertEquals(neverIngested, citation.eli)
        assertNull(citation.title)
        assertNull(citation.act)
    }

    @Test
    fun `what has changed an act comes back newest first`() {
        val id = record(BUILDING_LAW, "Prawo budowlane")
        val older = Eli("DU/2025/100")
        val newer = Eli("DU/2026/200")
        record(older, "Zmiana z 2025", announcedOn = LocalDate.of(2025, 5, 1))
        record(newer, "Zmiana z 2026", announcedOn = LocalDate.of(2026, 5, 1))

        references.replaceReferencesFrom(older, listOf(ActReferenceEdge(older, BUILDING_LAW, ActRelation.AMENDS)), statedBy)
        references.replaceReferencesFrom(newer, listOf(ActReferenceEdge(newer, BUILDING_LAW, ActRelation.REPEALS)), statedBy)

        assertEquals(listOf(newer, older), cards.cardFor(id).changedBy.map { it.eli })
    }

    /** The loop the whole identity machinery exists to close: law back to draft. */
    @Test
    fun `the card names the draft the act was`() {
        val id = record(BUILDING_LAW, "Prawo budowlane")
        val draft = drafts.insertDraft(
            DraftFromRegister(
                title = "Rządowy projekt ustawy Prawo budowlane",
                initiator = DraftInitiator.GOVERNMENT,
                term = 10,
                startedOn = LocalDate.of(2024, 1, 10),
            ),
        )
        drafts.linkToAct(draft, id)

        assertEquals(draft, cards.cardFor(id).draft)
    }

    @Test
    fun `the numbers the act is quoted by are on the card`() {
        val id = record(BUILDING_LAW, "Prawo budowlane")
        identifiers.pointAtAct(IdentifierScheme.SEJM_PRINT, "term10/print/424", id, MatchMethod.EXACT, null)

        val identifier = cards.cardFor(id).identifiers.single { it.scheme == IdentifierScheme.SEJM_PRINT }

        assertEquals("term10/print/424", identifier.value)
        assertEquals("exact", identifier.resolvedBy)
    }

    @Test
    fun `an act nobody published is not found`() {
        assertFailsWith<UnknownActException> { cards.cardFor(Eli("DU/2099/1")) }
    }

    /**
     * The whole reason corpus compares anything: a reader looking at a law wants to know
     * what the journal changed in it, and until now the comparison existed and nothing
     * could reach it — the id of the document an act was read from was thrown away the
     * moment it had been used to cite a reference.
     */
    @Test
    fun `the card carries what changed in the newest text of the act`() {
        val document = DocumentId(Ids.next())
        val act = record(Eli("DU/2024/1222"), "Ustawa o cenach energii", readFrom = document)
        val diff = diffs.compared(document, changes = 41, substantive = 3, at = Instant.parse("2026-03-02T10:00:00Z"))

        val card = cards.cardFor(act)

        assertEquals(diff.id, card.latestChange?.id)
        assertEquals(3, card.latestChange?.substantiveChanges)
        assertEquals(41, card.latestChange?.changeCount)
    }

    /** One text and nothing to compare it with, which is most acts. */
    @Test
    fun `an act corpus has never compared says nothing about changes`() {
        val act = record(Eli("DU/2024/1223"), "Ustawa o czymś innym")

        assertNull(cards.cardFor(act).latestChange)
    }

    private fun record(
        eli: Eli,
        title: String,
        announcedOn: LocalDate? = LocalDate.of(2024, 8, 1),
        appliesFrom: LocalDate? = LocalDate.of(2024, 9, 1),
        readFrom: DocumentId = DocumentId(Ids.next()),
    ) = acts.actFor(
        EliActMetadata(
            eli = eli,
            title = title,
            type = "Ustawa",
            announcedOn = announcedOn,
            inForceFrom = appliesFrom,
            prints = emptyList(),
            references = emptyList(),
            unmappedLabels = emptyList(),
        ),
        readFrom,
    )

    private companion object {
        val BUILDING_LAW = Eli("DU/2024/1222")
        val AMENDMENT = Eli("DU/2025/7")
    }
}
