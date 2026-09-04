package pl.barometr.legislative.internal

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one claim in this system a reader is most likely to act on, read out of prose.
 *
 * The letters below are the shapes ministries actually send: a term stated as a
 * period, as a date, as both, spelled out in words, and — the case that matters most —
 * a bill's own text full of dates that are nobody's deadline.
 */
class ConsultationLetterReaderTest {

    private val reader = ConsultationLetterReader()

    @Test
    fun `a term stated as a period is read with the day it runs from`() {
        val letter = reader.readLetter(PERIOD_LETTER)

        assertEquals(ConsultationTerm.Period(21), letter?.term)
        assertEquals(LocalDate.of(2026, 4, 9), letter?.writtenOn, "the letter's own dateline")
        assertEquals(LocalDate.of(2026, 4, 30), letter?.closingDay())
    }

    /**
     * `Warszawa, dnia 9 kwietnia 2026 r.` is what a ministry's template prints, and a
     * period with no day to count from is a number rather than a term — so the longer
     * form has to be read or half these letters go undated.
     */
    @Test
    fun `a dateline is recognised whether or not it says dnia`() {
        val withoutDnia = PERIOD_LETTER.replace("Warszawa, dnia 9", "Warszawa, 9")

        assertEquals(LocalDate.of(2026, 4, 9), reader.readLetter(withoutDnia)?.writtenOn)
    }

    /**
     * The first date in a document is the wrong rule: a letter opens by naming the
     * draft it encloses, and that draft carries its own date.
     */
    @Test
    fun `a date the letter merely mentions is not its dateline`() {
        val letter = reader.readLetter(
            """
            Projekt z dnia 3 marca 2026 r.

            Warszawa, dnia 9 kwietnia 2026 r.

            Uprzejmie proszę o zgłoszenie uwag w terminie 21 dni od dnia otrzymania pisma.
            """.trimIndent(),
        )

        assertEquals(LocalDate.of(2026, 4, 9), letter?.writtenOn)
    }

    /**
     * A letter stating both has committed to the date, and the date is the reading that
     * needs no dateline to resolve. The abbreviation between them is the whole
     * difficulty: a full stop taken at `tj.` would cut the date away from the request
     * for a position and leave only the period.
     */
    @Test
    fun `a date stated beside a period is the one believed`() {
        val letter = reader.readLetter(
            """
            Warszawa, dnia 2 marca 2026 r.

            Uprzejmie proszę o przekazanie stanowiska w terminie 21 dni, tj. do dnia 30 kwietnia 2026 r.
            """.trimIndent(),
        )

        assertEquals(ConsultationTerm.ClosingDate(LocalDate.of(2026, 4, 30)), letter?.term)
        assertEquals(LocalDate.of(2026, 4, 30), letter?.closingDay(), "a stated date needs no arithmetic")
    }

    @Test
    fun `a term spelled out in words is read as a number of days`() {
        val letter = reader.readLetter(
            """
            Warszawa, dnia 9 kwietnia 2026 r.

            Z uwagi na pilny charakter projektu uprzejmie proszę o zgłoszenie uwag
            w terminie siedmiu dni od dnia otrzymania niniejszego pisma.
            """.trimIndent(),
        )

        assertEquals(ConsultationTerm.Period(7), letter?.term)
    }

    @Test
    fun `a closing date written in digits is read`() {
        val letter = reader.readLetter(
            """
            Warszawa, dnia 2 marca 2026 r.

            Uprzejmie proszę o zgłoszenie ewentualnych uwag do dnia 15.03.2026 r.
            """.trimIndent(),
        )

        assertEquals(ConsultationTerm.ClosingDate(LocalDate.of(2026, 3, 15)), letter?.term)
    }

    /**
     * The failure this class exists to prevent. A bill's own text states dates in every
     * transitional provision it has, and none of them is a deadline for anybody.
     */
    @Test
    fun `a date in a transitional provision is not a deadline`() {
        val provisions = """
            Art. 12. Przepisy art. 3-7 stosuje się do dnia 31 grudnia 2027 r.
            Art. 13. Ustawa wchodzi w życie po upływie 14 dni od dnia ogłoszenia.
        """.trimIndent()

        assertNull(reader.readLetter(provisions))
    }

    /**
     * Three digits are allowed through the pattern so that "w terminie 120 dni" is read
     * whole rather than half-read as 12. What comes back through it has to be a term
     * somebody would actually set.
     */
    @Test
    fun `a period nobody would set is not a term`() {
        val letter = reader.readLetter(
            """
            Warszawa, dnia 9 kwietnia 2026 r.

            Uprzejmie proszę o zgłoszenie uwag w terminie 500 dni od dnia otrzymania pisma.
            """.trimIndent(),
        )

        assertNull(letter)
    }

    /**
     * A period is not a term until something says what to count it from, and inventing
     * that day is exactly the quiet error the whole provenance chain exists to prevent.
     */
    @Test
    fun `a period with no dateline yields no closing day`() {
        val letter = reader.readLetter(
            "Uprzejmie proszę o zgłoszenie uwag w terminie 21 dni od dnia otrzymania pisma.",
        )

        assertEquals(ConsultationTerm.Period(21), letter?.term)
        assertNull(letter?.writtenOn)
        assertNull(letter?.closingDay(), "there is no day to count twenty-one from")
    }

    @Test
    fun `the quote is exactly the characters the offsets point at`() {
        val letter = reader.readLetter(PERIOD_LETTER)!!

        assertEquals(letter.quote, PERIOD_LETTER.substring(letter.charStart, letter.charEnd))
        assertTrue(letter.quote.contains("w terminie 21 dni"), "the term is inside the sentence quoted")
        assertTrue(letter.quote.contains("uwag"), "so is what makes it a request for comments")
    }

    @Test
    fun `an address named beside the request for comments is where comments go`() {
        assertEquals("konsultacje@ms.gov.pl", reader.readLetter(PERIOD_LETTER)?.submissionAddress)
    }

    /**
     * Every ministerial letter carries a switchboard address in its footer. Reporting
     * that as the place to send comments would send somebody's submission into a
     * mailbox nobody reads it in.
     */
    @Test
    fun `an address in the footer is not the address for comments`() {
        val letter = reader.readLetter(
            """
            Warszawa, dnia 9 kwietnia 2026 r.

            Uprzejmie przekazuję projekt rozporządzenia i proszę o zgłoszenie uwag
            w terminie 30 dni od dnia otrzymania niniejszego pisma. Formularz
            zgłoszeniowy znajduje się na stronie projektu w Rządowym Procesie
            Legislacyjnym, gdzie zamieszczono również ocenę skutków regulacji oraz
            uzasadnienie. Projekt jest procedowany w trybie odrębnym, o czym
            informowano na posiedzeniu Komitetu Stałego Rady Ministrów w dniu
            3 marca 2026 r.

            Z wyrazami szacunku
            Podsekretarz Stanu

            Ministerstwo Sprawiedliwości, Al. Ujazdowskie 11, 00-950 Warszawa,
            kancelaria@ms.gov.pl
            """.trimIndent(),
        )

        assertEquals(ConsultationTerm.Period(30), letter?.term, "the term is still read")
        assertNull(letter?.submissionAddress)
    }

    private companion object {
        val PERIOD_LETTER = """
            Warszawa, dnia 9 kwietnia 2026 r.

            MINISTER SPRAWIEDLIWOŚCI
            DL-II.4601.3.2026

            Szanowni Państwo,

            uprzejmie przekazuję w załączeniu projekt ustawy o zmianie ustawy o dostępie
            do informacji publicznej i zwracam się z prośbą o zgłoszenie uwag
            w terminie 21 dni od dnia otrzymania niniejszego pisma.
            Uwagi proszę przesyłać na adres konsultacje@ms.gov.pl w wersji edytowalnej.

            Z poważaniem
            Minister Sprawiedliwości
        """.trimIndent()
    }
}
