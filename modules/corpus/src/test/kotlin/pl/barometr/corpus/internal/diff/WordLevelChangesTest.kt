package pl.barometr.corpus.internal.diff

import pl.barometr.corpus.api.ChangeKind
import pl.barometr.corpus.internal.structure.EditorialUnitReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The detail a reader actually came for: not "this paragraph changed" but "the term is
 * thirty days rather than fourteen".
 *
 * Every assertion here is about the ranges, because a range that does not quote what it
 * claims to quote is worse than no highlight at all.
 */
class WordLevelChangesTest {

    private val reader = EditorialUnitReader()

    @Test
    fun `a replaced word is quoted on both sides`() {
        val before = "Art. 5. Wniosek składa się w terminie 14 dni."
        val after = "Art. 5. Wniosek składa się w terminie 30 dni."

        val change = changesBetween(before, after).changes.single()

        assertEquals(ChangeKind.MODIFIED, change.kind)
        assertEquals("14", before.substring(change.fromCharStart!!, change.fromCharEnd!!))
        assertEquals("30", after.substring(change.toCharStart!!, change.toCharEnd!!))
    }

    @Test
    fun `an inserted phrase has no older side`() {
        val before = "Art. 5. Wniosek składa się w terminie 14 dni."
        val after = "Art. 5. Wniosek składa się na piśmie w terminie 14 dni."

        val change = changesBetween(before, after).changes.single()

        assertEquals(ChangeKind.ADDED, change.kind)
        assertEquals(null, change.fromCharStart)
        assertEquals("na piśmie", after.substring(change.toCharStart!!, change.toCharEnd!!))
    }

    @Test
    fun `a deleted phrase has no newer side`() {
        val before = "Art. 5. Wniosek składa się na piśmie w terminie 14 dni."
        val after = "Art. 5. Wniosek składa się w terminie 14 dni."

        val change = changesBetween(before, after).changes.single()

        assertEquals(ChangeKind.REMOVED, change.kind)
        assertEquals("na piśmie", before.substring(change.fromCharStart!!, change.fromCharEnd!!))
        assertEquals(null, change.toCharStart)
    }

    @Test
    fun `a unit changed past listing is reported whole, and says so`() {
        // Scattered changes rather than one long replacement: what is counted is the
        // number of separate runs a reader would have to follow, and a paragraph
        // rewritten in one go is a single run however long it is.
        val before = "Art. 5. " + (1..60).joinToString(" ") { "słowo$it" } + "."
        val after = "Art. 5. " + (1..60).joinToString(" ") { if (it % 2 == 0) "inne$it" else "słowo$it" } + "."

        val changes = WordLevelChanges(DiffProperties(maxWordChanges = 5)).changesWithin(
            readingOf(before),
            readingOf(after),
        )

        assertTrue(changes.truncated)
        val whole = changes.changes.single()
        assertEquals(before.length, whole.fromCharEnd)
        assertEquals(after.length, whole.toCharEnd)
    }

    @Test
    fun `a rewrapped line is not a word change`() {
        val before = "Art. 5. Wniosek składa się\nw terminie 14 dni."
        val after = "Art. 5. Wniosek składa\nsię w terminie 14 dni."

        assertEquals(emptyList(), changesBetween(before, after).changes)
    }

    private fun changesBetween(before: String, after: String): WordChanges =
        WordLevelChanges(DiffProperties()).changesWithin(readingOf(before), readingOf(after))

    /** The first unit of a one-unit text, which is what these fixtures are. */
    private fun readingOf(text: String) =
        UnitReading.of(text, reader.unitsIn(text).first())
}
