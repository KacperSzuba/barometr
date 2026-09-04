package pl.barometr.shared

import java.time.LocalDate

/**
 * The days Polish law says nobody works.
 *
 * Here rather than in a library, and that is a decision rather than an oversight.
 * Jollyday is the obvious candidate and was read: it carries Poland, but it reads its
 * calendar from XML through either a JAXB runtime or Jackson 2 — and this classpath is
 * Boot 4, which ships Jackson 3 under `tools.jackson`. Two megabytes of dependency and
 * a second JSON stack, to answer thirteen dates a statute lists by name, is the wrong
 * trade. Nothing in the JDK or in Spring answers it at all.
 *
 * The list is `ustawa z dnia 18 stycznia 1951 r. o dniach wolnych od pracy`, and it is
 * short because that statute is: nine fixed days, four fixed to Easter, and Christmas
 * Eve since the 2024 amendment. Sundays are days off under the same article and are
 * not listed here — a weekday is not what this answers.
 *
 * Every date is computed, so the answer is as right for 2031 as for 2021. That matters
 * here: the archive reaches five years back, and a table of dates somebody maintains
 * by hand goes wrong in exactly the years nobody is looking at.
 */
object PolishPublicHolidays {

    fun isHoliday(date: LocalDate): Boolean = date in holidaysIn(date.year)

    /** Every statutory day off in [year], Sundays excepted. */
    fun holidaysIn(year: Int): Set<LocalDate> {
        val easter = easterSundayIn(year)

        return buildSet {
            add(LocalDate.of(year, 1, 1))    // Nowy Rok
            add(LocalDate.of(year, 1, 6))    // Trzech Króli
            add(easter)                      // Wielkanoc
            add(easter.plusDays(1))          // Poniedziałek Wielkanocny
            add(LocalDate.of(year, 5, 1))    // Święto Państwowe
            add(LocalDate.of(year, 5, 3))    // Święto Narodowe Trzeciego Maja
            add(easter.plusDays(49))         // Zielone Świątki
            add(easter.plusDays(60))         // Boże Ciało
            add(LocalDate.of(year, 8, 15))   // Wniebowzięcie NMP
            add(LocalDate.of(year, 11, 1))   // Wszystkich Świętych
            add(LocalDate.of(year, 11, 11))  // Narodowe Święto Niepodległości
            // Christmas Eve, added by the amendment of 6 December 2024 and free from
            // 2025 on. Conditioned on the year rather than simply listed, because a
            // consultation term counted over Christmas 2023 would otherwise be given a
            // day the law had not yet granted.
            if (year >= CHRISTMAS_EVE_FREE_FROM) add(LocalDate.of(year, 12, 24))
            add(LocalDate.of(year, 12, 25))  // Boże Narodzenie
            add(LocalDate.of(year, 12, 26))  // drugi dzień Bożego Narodzenia
        }
    }

    /**
     * Easter Sunday in the Gregorian calendar, by the anonymous Gregorian computus.
     *
     * Four of the thirteen days hang off this one, and it moves by more than a month
     * between years — which is why the whole set is computed rather than tabulated.
     * The algorithm is the standard one (Meeus/Jones/Butcher); its intermediate names
     * are the ones the literature uses, and inventing better ones would only make it
     * impossible to check against the source.
     */
    fun easterSundayIn(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451

        return LocalDate.of(year, (h + l - 7 * m + 114) / 31, ((h + l - 7 * m + 114) % 31) + 1)
    }

    /** The first year Christmas Eve was free from work. */
    private const val CHRISTMAS_EVE_FREE_FROM = 2025
}
