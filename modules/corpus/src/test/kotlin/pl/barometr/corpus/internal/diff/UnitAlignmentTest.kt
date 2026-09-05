package pl.barometr.corpus.internal.diff

import pl.barometr.corpus.api.ChangeKind
import pl.barometr.corpus.internal.structure.EditorialUnitReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pass the whole feature is judged on.
 *
 * The specification's acceptance criterion is one sentence — a renumbered article must
 * not be reported as a deletion — and every test here is a way that could go wrong.
 */
class UnitAlignmentTest {

    private val reader = EditorialUnitReader()
    private val alignment = UnitAlignment(DiffProperties())

    @Test
    fun `an article renumbered by an insertion is moved, not removed and added`() {
        val before = """
            Art. 5. Przedsiębiorca prowadzi ewidencję odpadów w postaci elektronicznej.
            Art. 6. Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia.
        """.trimIndent()
        val after = """
            Art. 5. Przedsiębiorca prowadzi ewidencję odpadów w postaci elektronicznej.
            Art. 6. Minister właściwy do spraw klimatu prowadzi rejestr przedsiębiorców.
            Art. 7. Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia.
        """.trimIndent()

        val changes = align(before, after)

        assertEquals(0, changes.count { it.kind == ChangeKind.REMOVED }, "nothing was removed: $changes")
        assertEquals(1, changes.count { it.kind == ChangeKind.ADDED })
        val moved = changes.single { it.kind == ChangeKind.MOVED }
        assertEquals("art-6", moved.before?.path)
        assertEquals("art-7", moved.after?.path)
    }

    @Test
    fun `a renumbering that changes nothing else is never substantive`() {
        val before = "Art. 6. Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia."
        val after = "Art. 7. Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia."

        assertEquals(listOf(false), align(before, after).map { it.substantive })
    }

    @Test
    fun `a reworded paragraph keeps its place and is reported as modified`() {
        val before = """
            Art. 5. 1. Wniosek składa się w terminie 14 dni od dnia doręczenia decyzji.
            2. Wniosek zawiera uzasadnienie.
        """.trimIndent()
        val after = """
            Art. 5. 1. Wniosek składa się w terminie 30 dni od dnia doręczenia decyzji.
            2. Wniosek zawiera uzasadnienie.
        """.trimIndent()

        val change = align(before, after).single()

        assertEquals(ChangeKind.MODIFIED, change.kind)
        assertEquals("art-5/ust-1", change.after?.path)
        assertTrue(change.substantive)
    }

    @Test
    fun `a change of punctuation is reported, and is not substantive`() {
        val before = "Art. 5. Podmiot który prowadzi rejestr przekazuje dane ministrowi."
        val after = "Art. 5. Podmiot, który prowadzi rejestr, przekazuje dane ministrowi."

        val change = align(before, after).single()

        assertEquals(ChangeKind.MODIFIED, change.kind)
        assertEquals(false, change.substantive)
    }

    @Test
    fun `a document that did not change produces no changes at all`() {
        val text = """
            Art. 5. 1. Wniosek składa się w terminie 14 dni.
            2. Wniosek zawiera uzasadnienie.
            Art. 6. Ustawa wchodzi w życie po upływie 14 dni.
        """.trimIndent()

        assertEquals(emptyList(), align(text, text))
    }

    @Test
    fun `a deleted article is a removal, and the rest is not disturbed`() {
        val before = """
            Art. 5. Przedsiębiorca prowadzi ewidencję odpadów w postaci elektronicznej.
            Art. 6. Minister właściwy do spraw klimatu prowadzi rejestr przedsiębiorców.
            Art. 7. Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia.
        """.trimIndent()
        val after = """
            Art. 5. Przedsiębiorca prowadzi ewidencję odpadów w postaci elektronicznej.
            Art. 6. Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia.
        """.trimIndent()

        val changes = align(before, after)

        assertEquals(1, changes.count { it.kind == ChangeKind.REMOVED })
        assertEquals("art-6", changes.single { it.kind == ChangeKind.REMOVED }.before?.path)
        assertEquals(1, changes.count { it.kind == ChangeKind.MOVED })
        assertEquals(0, changes.count { it.kind == ChangeKind.ADDED })
    }

    @Test
    fun `an article rewritten in place is one modification, not a removal and an addition`() {
        val before = "Art. 5. Przedsiębiorca prowadzi ewidencję odpadów w postaci elektronicznej."
        val after = "Art. 5. Wojewoda ogłasza w dzienniku urzędowym wykaz gmin objętych programem."

        val changes = align(before, after)

        // The paths agree, so this is one unit that says something else — not two
        // units. Alignment by path is what says so, and it is right to: the document
        // itself claims they are the same article.
        assertEquals(listOf(ChangeKind.MODIFIED), changes.map { it.kind })
        assertTrue(changes.single().substantive)
    }

    @Test
    fun `a block moved to another chapter is found by what it says`() {
        val before = """
            Rozdział 1
            Przepisy ogólne

            Art. 1. Przedsiębiorca prowadzi ewidencję odpadów w postaci elektronicznej.

            Rozdział 2
            Przepisy szczegółowe

            Art. 2. Minister ogłasza wykaz podmiotów zwolnionych z obowiązku ewidencji.
        """.trimIndent()
        val after = """
            Rozdział 1
            Przepisy ogólne

            Art. 1. Minister ogłasza wykaz podmiotów zwolnionych z obowiązku ewidencji.

            Rozdział 2
            Przepisy szczegółowe

            Art. 2. Przedsiębiorca prowadzi ewidencję odpadów w postaci elektronicznej.
        """.trimIndent()

        val changes = align(before, after)

        assertEquals(0, changes.count { it.kind == ChangeKind.REMOVED }, changes.toString())
        assertEquals(0, changes.count { it.kind == ChangeKind.ADDED }, changes.toString())
        assertEquals(2, changes.count { it.kind == ChangeKind.MOVED })
    }

    @Test
    fun `a removal is written where the text it left was`() {
        val before = """
            Art. 1. Pierwszy przepis o ewidencji odpadów prowadzonej przez przedsiębiorcę.
            Art. 2. Drugi przepis o rejestrze prowadzonym przez ministra właściwego.
            Art. 3. Trzeci przepis o wejściu ustawy w życie po upływie czternastu dni.
        """.trimIndent()
        val after = """
            Art. 1. Pierwszy przepis o ewidencji odpadów prowadzonej przez przedsiębiorcę.
            Art. 2. Trzeci przepis o wejściu ustawy w życie po upływie czternastu dni.
        """.trimIndent()

        val changes = align(before, after)

        assertEquals(ChangeKind.REMOVED, changes.first().kind, "the deletion is read before what follows it")
        assertEquals("art-2", changes.first().before?.path)
    }

    private fun align(before: String, after: String): List<AlignedUnits> =
        alignment.alignedTo(readingsOf(before), readingsOf(after))

    private fun readingsOf(text: String) =
        reader.unitsIn(text).map { UnitReading.of(text, it) }
}
