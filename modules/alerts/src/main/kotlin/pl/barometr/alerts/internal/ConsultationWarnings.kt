package pl.barometr.alerts.internal

/**
 * How often a closing consultation is worth mentioning, and how far out.
 *
 * Three warnings, because one is never the right number. A month out is when there is
 * still time to read the draft, ask somebody and write something — but it is also far
 * enough away to be forgotten, which is what the second is for. The last is the one
 * that gets acted on, and by then the only useful unit is how many chances to file
 * remain.
 *
 * **Counted in working days throughout**, including the far ones where it hardly
 * matters, because the alternative is two units in one feature: a reader comparing
 * "a month" against "three days" would be comparing two different kinds of day, and
 * whoever next changes a number here would have to notice which one they were holding.
 * Twenty working days is about four weeks; ten is about a fortnight.
 *
 * [bandFor] is what makes a run that missed a day still correct. A consultation is not
 * warned about *at* twenty days but *within* twenty, so the warning fires at the first
 * sight inside a band however late that is — a system down for a week resumes and
 * sends the warning it owes, rather than skipping it silently because the exact day
 * passed while it was off.
 */
object ConsultationWarnings {

    /**
     * Widest first, which is the order [bandFor] narrows through and the order they
     * happen in.
     */
    val MARKS = listOf(20, 10, 3)

    /**
     * Which warning a consultation with [workingDaysLeft] to run is due, or null when
     * it is further off than the first of them.
     *
     * The narrowest band that still contains it, so a consultation first seen with
     * fifteen days left gets the month's warning now and the other two in their turn,
     * and one seen at seven has already missed nothing — the ten-day warning is what it
     * is owed, and it is what it gets.
     */
    fun bandFor(workingDaysLeft: Int): Int? = MARKS.lastOrNull { workingDaysLeft <= it }
}
