package pl.barometr.legislative.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Which prints an agenda item names — the only join there is between a vote and a bill,
 * so what it reads and what it refuses to read are both worth pinning down.
 *
 * The titles below are the Sejm's own, taken from the voting record of the tenth term.
 */
class CitedPrintsTest {

    @Test
    fun `a list of prints is read whole`() {
        val title = "Pkt. 3 Wybór Wicemarszałków Sejmu RP (druki nr 3, 4, 5, 6, 7 i 8)"

        assertEquals(
            listOf(3, 4, 5, 6, 7, 8).map { "term10/print/$it" },
            CitedPrints.addressesIn(title, term = 10),
        )
    }

    @Test
    fun `a pair joined by i is two prints`() {
        val title = "Pkt. 2 Poselskie projekty uchwał w sprawie ustalenia liczby Wicemarszałków Sejmu RP (druki nr 1 i 2)"

        assertEquals(listOf("term10/print/1", "term10/print/2"), CitedPrints.addressesIn(title, term = 10))
    }

    @Test
    fun `a single print is read in the singular`() {
        assertEquals(
            listOf("term10/print/424"),
            CitedPrints.addressesIn("Pkt. 12 Sprawozdanie Komisji o rządowym projekcie ustawy (druk nr 424)", 10),
        )
    }

    /** The Sejm letters a print it has had to correct, and the letter is part of its name. */
    @Test
    fun `a corrected print keeps its letter`() {
        assertEquals(listOf("term10/print/424-A"), CitedPrints.addressesIn("Sprawozdanie (druk nr 424-A)", 10))
    }

    /**
     * The reason a citation is matched as a list of numbers rather than as everything up
     * to a bracket: a title is prose, and prose is full of numbers that are not prints.
     */
    @Test
    fun `numbers that are not prints are not read as prints`() {
        val title = "Pkt. 5 Sprawozdanie o zmianie ustawy z dnia 26 lipca 1991 r. o podatku dochodowym (druk nr 512)"

        assertEquals(listOf("term10/print/512"), CitedPrints.addressesIn(title, term = 10))
    }

    @Test
    fun `an item that names no print cites nothing`() {
        assertEquals(emptyList(), CitedPrints.addressesIn("Pkt. 1 Wybór Marszałka Sejmu RP", term = 10))
    }

    @Test
    fun `a print named twice is cited once`() {
        val title = "Sprawozdanie Komisji o projekcie (druk nr 7) wraz z poprawkami do druku nr 7"

        assertEquals(listOf("term10/print/7"), CitedPrints.addressesIn(title, term = 10))
    }

    /** The address carries the term, so the same number in two terms is two prints. */
    @Test
    fun `a print is addressed within its own term`() {
        assertEquals(listOf("term9/print/1"), CitedPrints.addressesIn("Sprawozdanie (druk nr 1)", term = 9))
    }
}
