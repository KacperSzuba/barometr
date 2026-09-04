package pl.barometr.shared

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Checked against dates a calendar can be held up to, because the whole reason this
 * is computed rather than listed is that nobody would notice a wrong year until a
 * consultation deadline was printed a day late.
 */
class PolishPublicHolidaysTest {

    /**
     * Easter is where the four movable days come from, and it swings by more than a
     * month. These are the published dates for four years chosen to sit far apart —
     * an early one, a late one, and a century boundary the computus is famous for
     * getting wrong when it is copied carelessly.
     */
    @Test
    fun `Easter falls where the calendar says it does`() {
        assertEquals(LocalDate.of(2024, 3, 31), PolishPublicHolidays.easterSundayIn(2024))
        assertEquals(LocalDate.of(2025, 4, 20), PolishPublicHolidays.easterSundayIn(2025))
        assertEquals(LocalDate.of(2026, 4, 5), PolishPublicHolidays.easterSundayIn(2026))
        assertEquals(LocalDate.of(2000, 4, 23), PolishPublicHolidays.easterSundayIn(2000))
        assertEquals(LocalDate.of(2038, 4, 25), PolishPublicHolidays.easterSundayIn(2038))
    }

    @Test
    fun `the days that hang off Easter move with it`() {
        // 2026: Easter on 5 April, so Easter Monday on the 6th, Pentecost on 24 May
        // and Corpus Christi on the Thursday of 4 June.
        assertTrue(PolishPublicHolidays.isHoliday(LocalDate.of(2026, 4, 6)))
        assertTrue(PolishPublicHolidays.isHoliday(LocalDate.of(2026, 5, 24)))
        assertTrue(PolishPublicHolidays.isHoliday(LocalDate.of(2026, 6, 4)))
    }

    @Test
    fun `the fixed days are the ones the statute names`() {
        listOf(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 6),
            LocalDate.of(2026, 5, 1),
            LocalDate.of(2026, 5, 3),
            LocalDate.of(2026, 8, 15),
            LocalDate.of(2026, 11, 1),
            LocalDate.of(2026, 11, 11),
            LocalDate.of(2026, 12, 25),
            LocalDate.of(2026, 12, 26),
        ).forEach { assertTrue(PolishPublicHolidays.isHoliday(it), "$it is a statutory day off") }
    }

    /**
     * The amendment of 6 December 2024 freed Christmas Eve from 2025 on. The archive
     * reaches back five years, so a term counted over Christmas 2023 must not be given
     * a day the law had not yet granted.
     */
    @Test
    fun `Christmas Eve is free from 2025 and not before`() {
        assertTrue(PolishPublicHolidays.isHoliday(LocalDate.of(2025, 12, 24)))
        assertTrue(PolishPublicHolidays.isHoliday(LocalDate.of(2026, 12, 24)))
        assertFalse(PolishPublicHolidays.isHoliday(LocalDate.of(2023, 12, 24)))
    }

    @Test
    fun `an ordinary weekday is not a day off`() {
        assertFalse(PolishPublicHolidays.isHoliday(LocalDate.of(2026, 4, 9)))
        assertFalse(PolishPublicHolidays.isHoliday(LocalDate.of(2026, 7, 1)))
    }
}
