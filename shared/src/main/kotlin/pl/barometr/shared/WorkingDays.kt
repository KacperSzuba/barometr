package pl.barometr.shared

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Counting in days that count.
 *
 * A consultation deadline is a legal term, and Polish law does not let one end on a
 * Saturday, a Sunday or a statutory day off: `art. 57 § 4 k.p.a.` moves it to the next
 * day that is none of those. A product that prints the ministry's arithmetic instead —
 * "twenty-one days from the ninth of April" landing on a Thursday that happens to be
 * Corpus Christi — tells somebody their comments are due a day before they are, which
 * is the one direction this error must never go.
 *
 * The term itself still runs in calendar days. Only its *end* is moved, which is what
 * the article says and is a narrower rule than "count working days" — getting that
 * backwards would shift a 30-day consultation by six weeks.
 */
object WorkingDays {

    fun isWorkingDay(date: LocalDate): Boolean =
        date.dayOfWeek != DayOfWeek.SATURDAY &&
            date.dayOfWeek != DayOfWeek.SUNDAY &&
            !PolishPublicHolidays.isHoliday(date)

    /**
     * Where a term that would expire on [date] actually expires.
     *
     * Returns [date] itself when it is already a working day, which is the usual case
     * and the reason this is safe to apply to every deadline rather than only to the
     * ones somebody noticed.
     */
    fun endOfTerm(date: LocalDate): LocalDate {
        var end = date
        // Bounded in practice by the longest run of days off Poland can produce — a
        // Christmas that falls beside a weekend — and a loop rather than a table
        // because that run changes with the calendar.
        while (!isWorkingDay(end)) end = end.plusDays(1)

        return end
    }

    /**
     * Working days from [from] up to and including [until]; zero once [until] is past.
     *
     * Inclusive of the last day because that is how a deadline is read: a term ending
     * on Friday leaves one working day on Friday morning, not none. What a reader
     * wants from "three days left" is how many chances there still are to file, and
     * the day it is due is one of them.
     */
    fun between(from: LocalDate, until: LocalDate): Int {
        if (until.isBefore(from)) return 0

        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(until) }
            .count(::isWorkingDay)
    }
}
