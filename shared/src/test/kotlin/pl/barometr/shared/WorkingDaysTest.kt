package pl.barometr.shared

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * The arithmetic a consultation deadline is printed from. Wrong in the early
 * direction it tells somebody their comments were due before they were, which is why
 * every case here is a boundary rather than a middle.
 */
class WorkingDaysTest {

    @Test
    fun `a term ending on a working day ends there`() {
        // A Thursday, and nothing about it.
        assertEquals(LocalDate.of(2026, 4, 30), WorkingDays.endOfTerm(LocalDate.of(2026, 4, 30)))
    }

    @Test
    fun `a term ending on a Saturday runs to the Monday`() {
        assertEquals(LocalDate.of(2026, 4, 27), WorkingDays.endOfTerm(LocalDate.of(2026, 4, 25)))
    }

    /**
     * 1 May 2026 is a Friday and a statutory day off; the Saturday and Sunday follow,
     * and Monday the 4th is the first day anything can be filed.
     */
    @Test
    fun `a term ending on a holiday runs past the weekend behind it`() {
        assertEquals(LocalDate.of(2026, 5, 4), WorkingDays.endOfTerm(LocalDate.of(2026, 5, 1)))
    }

    @Test
    fun `the day a term is due still counts as a day to file on`() {
        val friday = LocalDate.of(2026, 4, 10)

        assertEquals(1, WorkingDays.between(friday, friday))
    }

    @Test
    fun `weekends and holidays are not days left`() {
        // Thursday 30 April to Monday 4 May 2026: the Thursday, then a Friday that is
        // Labour Day, a weekend, and the Monday. Two days to work in.
        assertEquals(
            2,
            WorkingDays.between(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 4)),
        )
    }

    @Test
    fun `a term already past leaves nothing`() {
        assertEquals(
            0,
            WorkingDays.between(LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 9)),
        )
    }
}
