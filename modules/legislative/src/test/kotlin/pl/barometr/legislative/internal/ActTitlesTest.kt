package pl.barometr.legislative.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The normalisation both sides of a title match go through.
 *
 * Pinned because the failure mode is silent: normalise the stored title and the query
 * differently and nothing breaks, matches just quietly get worse.
 */
class ActTitlesTest {

    @Test
    fun `diacritics are folded, including the one Unicode does not decompose`() {
        // ł is a letter in its own right, not l with a mark, so NFD leaves it alone.
        // Without spelling it out, "świadczeniach" and "swiadczeniach" stop matching.
        assertEquals(
            "ustawa o swiadczeniach zdrowotnych zlozonych",
            ActTitles.normalise("Ustawa o świadczeniach zdrowotnych złożonych"),
        )
    }

    @Test
    fun `punctuation and spacing collapse to single separators`() {
        assertEquals(
            "ustawa z dnia 17 lipca 2026 r o zmianie niektorych ustaw",
            ActTitles.normalise("Ustawa z dnia 17 lipca 2026 r. — o zmianie   niektórych ustaw."),
        )
    }

    @Test
    fun `the same title in either casing normalises to one string`() {
        assertEquals(
            ActTitles.normalise("PRAWO KOMUNIKACJI ELEKTRONICZNEJ"),
            ActTitles.normalise("Prawo komunikacji elektronicznej"),
        )
    }
}
