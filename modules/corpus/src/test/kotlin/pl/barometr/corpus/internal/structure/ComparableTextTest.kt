package pl.barometr.corpus.internal.structure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The reading that decides whether two units say the same thing — and, separately,
 * whether the difference is worth telling anybody about.
 */
class ComparableTextTest {

    @Test
    fun `a renumbered unit reads identically, because the designator is not part of what it says`() {
        val before = "Art. 5. Przedsiębiorca prowadzi ewidencję."
        val after = "Art. 6. Przedsiębiorca prowadzi ewidencję."

        assertEquals(comparable(before), comparable(after))
    }

    @Test
    fun `a word broken across a line break is one word, and its span covers both halves`() {
        val text = "Art. 5. Przedsię-\nbiorca prowadzi ewidencję."

        val words = ComparableText.wordsIn(text, 0, text.length)

        assertEquals("przedsiębiorca", words.first().value)
        assertEquals("Przedsię-\nbiorca", text.substring(words.first().charStart, words.first().charEnd))
    }

    @Test
    fun `where a line wraps is not a change`() {
        assertEquals(
            comparable("Art. 5. Przedsiębiorca prowadzi\newidencję."),
            comparable("Art. 5. Przedsiębiorca\nprowadzi ewidencję."),
        )
    }

    @Test
    fun `a comma is a change, but not a substantive one`() {
        val before = ComparableText.wordsIn("1. Podmiot który prowadzi rejestr.", 0, 33)
        val after = ComparableText.wordsIn("1. Podmiot, który prowadzi rejestr.", 0, 34)

        assertNotEquals(ComparableText.comparableOf(before), ComparableText.comparableOf(after))
        assertEquals(ComparableText.coreOf(before), ComparableText.coreOf(after))
    }

    @Test
    fun `a changed word is substantive`() {
        val before = ComparableText.wordsIn("1. Termin wynosi 14 dni.", 0, 24)
        val after = ComparableText.wordsIn("1. Termin wynosi 30 dni.", 0, 24)

        assertNotEquals(ComparableText.coreOf(before), ComparableText.coreOf(after))
    }

    private fun comparable(text: String) =
        ComparableText.comparableOf(ComparableText.wordsIn(text, 0, text.length))
}
