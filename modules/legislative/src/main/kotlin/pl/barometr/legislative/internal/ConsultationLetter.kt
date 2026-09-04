package pl.barometr.legislative.internal

import java.time.LocalDate

/**
 * What a consultation letter turned out to say, with the words it said it in.
 *
 * [charStart] and [charEnd] index the extracted text the corpus stored — the same
 * offsets a summary's citation uses — so whatever renders a deadline can put the
 * ministry's own sentence beside it instead of asking a reader to take the date on
 * trust.
 *
 * [writtenOn] is the letter's dateline, and it is load-bearing rather than decorative:
 * a term of twenty-one days is a term from a particular day, and this is the only day
 * the document itself offers. Null when the letter carries no dateline, which is what
 * makes a stated period unresolvable and therefore unrecorded.
 */
data class ConsultationLetter(
    val term: ConsultationTerm,
    val writtenOn: LocalDate?,
    val quote: String,
    val charStart: Int,
    val charEnd: Int,
    /** An address the letter names for comments. Null unless it says so in as many words. */
    val submissionAddress: String?,
) {
    init {
        require(charEnd > charStart) { "A citation spans characters, got $charStart..$charEnd" }
    }

    /**
     * The day comments are due, or null when the letter set a period and gave nothing
     * to count it from.
     *
     * The moving of the end off a Saturday or a statutory day off belongs to whoever
     * knows the calendar, not here — this returns the ministry's arithmetic and no
     * more.
     */
    fun closingDay(): LocalDate? = when (term) {
        is ConsultationTerm.ClosingDate -> term.on
        is ConsultationTerm.Period -> writtenOn?.plusDays(term.days.toLong())
    }
}
