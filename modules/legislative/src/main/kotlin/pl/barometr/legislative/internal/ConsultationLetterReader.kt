package pl.barometr.legislative.internal

import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Reads a deadline out of the letter that opened a consultation.
 *
 * RPL publishes no deadline field. It publishes the letter a ministry sent — a PDF
 * with a dateline, a paragraph of legal basis, and one sentence saying how long there
 * is to reply — and that sentence is the whole of what this product can promise about
 * a term. So the date is read from prose, and the sentence it was read from is carried
 * out with it: a reader who doubts a date is shown the ministry's own words rather
 * than asked to trust ours.
 *
 * **The date must be asked for in the same breath as the comments.** Everything filed
 * under a consultation stage passes through here, and a bill's own text is full of
 * dates — "stosuje się do dnia 31 grudnia 2027 r." is a transitional provision, not a
 * deadline for anybody. A candidate is only taken when the sentence around it asks for
 * comments, an opinion or a position, which is what a covering letter does and what a
 * statute never does.
 *
 * Pure and stateless. What it is given is the extracted text the corpus stored, so the
 * offsets it returns index that text exactly, the way a summary's citation does, and a
 * letter whose term is not recognised yields null rather than a plausible thirty days.
 */
@Component
class ConsultationLetterReader {

    fun readLetter(text: String): ConsultationLetter? {
        val (term, cited) = candidatesIn(text)
            .firstOrNull { (_, cited) -> ASKS_FOR_COMMENTS.containsMatchIn(cited.textIn(text)) }
            ?: return null

        return ConsultationLetter(
            term = term,
            writtenOn = readDateline(text),
            quote = cited.textIn(text),
            charStart = cited.start,
            charEnd = cited.end,
            submissionAddress = readSubmissionAddress(text),
        )
    }

    /**
     * Every term the letter might be stating, with the sentence each sits in.
     *
     * Stated dates first, and that ordering is the policy: a letter carrying both —
     * "w terminie 21 dni, tj. do dnia 30 kwietnia 2026 r." — has committed to the
     * date, and the date is also the reading that needs no dateline to resolve and so
     * cannot go quietly wrong.
     */
    private fun candidatesIn(text: String): Sequence<Pair<ConsultationTerm, Citation>> =
        (closingDatesIn(text) + periodsIn(text)).map { (term, at) -> term to sentenceAround(text, at) }

    private fun closingDatesIn(text: String): Sequence<Pair<ConsultationTerm, IntRange>> =
        CLOSING_DATES.asSequence().flatMap { pattern ->
            pattern.regex.findAll(text).mapNotNull { match ->
                pattern.read(match)?.let { ConsultationTerm.ClosingDate(it) to match.range }
            }
        }

    private fun periodsIn(text: String): Sequence<Pair<ConsultationTerm, IntRange>> =
        PERIODS.asSequence().flatMap { regex ->
            regex.findAll(text).mapNotNull { match ->
                daysIn(match.groupValues[1])?.let { ConsultationTerm.Period(it) to match.range }
            }
        }

    /**
     * The number of days, written either way.
     *
     * Ministries spell the short terms out — "w terminie siedmiu dni" — and a reader
     * that only understood digits would drop those letters silently, which is the
     * failure mode this whole class is written to avoid.
     */
    private fun daysIn(written: String): Int? {
        val normalised = written.lowercase().replace(WHITESPACE, " ")

        return normalised.toIntOrNull()?.takeIf { it in PLAUSIBLE_DAYS } ?: SPELLED_DAYS[normalised]
    }

    /**
     * "Warszawa, 09 kwietnia 2026 r." — the day the letter went out, and the only day
     * a stated period can honestly be counted from.
     *
     * Anchored to a place name at the start of a line, and looked for only near the
     * top, because a letter is full of other dates: the statute it cites, the register
     * entry it refers to. The first date in the document is the wrong rule; the first
     * date somebody wrote a place beside is the right one.
     */
    private fun readDateline(text: String): LocalDate? =
        DATELINE.find(text.take(DATELINE_WINDOW))?.let(::longDateIn)

    /**
     * An address only when the letter puts one beside the word "uwagi".
     *
     * Every ministerial letter carries a switchboard address in its footer, and
     * reporting that as the place to send comments would send somebody's submission
     * into a mailbox nobody reads it in. So the address has to sit within a sentence
     * or two of the request for comments, and otherwise there is none — which is the
     * true answer for most letters, whose comments go into a form on the draft's page.
     */
    private fun readSubmissionAddress(text: String): String? =
        ASKS_FOR_COMMENTS.findAll(text)
            .firstNotNullOfOrNull { mention ->
                EMAIL.find(text, mention.range.last)
                    ?.takeIf { it.range.first - mention.range.last <= ADDRESS_WINDOW }
                    ?.value
            }

    /**
     * The sentence the term was stated in, trimmed to characters that carry text.
     *
     * A line break is not a sentence end here, deliberately: this text came out of a
     * PDF, where every line wraps mid-sentence, and treating a newline as a boundary
     * would cut the request for comments away from the term it applies to — which is
     * exactly the phrase that tells the two apart.
     *
     * Neither is the full stop of an abbreviation, for the same reason and at closer
     * range. A ministry states both readings in one breath — "w terminie 21 dni, tj. do
     * dnia 30 kwietnia 2026 r." — and a stop taken at `tj.` cuts the date away from the
     * only words that make it a deadline rather than a number, so the date is dropped
     * and the reading falls back to the period. Hence the rule below: a stop followed
     * by a lower-case word is inside a sentence, not at the end of one.
     *
     * Bounded, because a document whose punctuation did not survive extraction would
     * otherwise quote a whole page.
     */
    private fun sentenceAround(text: String, term: IntRange): Citation {
        val floor = (term.first - QUOTE_REACH).coerceAtLeast(0)
        val ceiling = (term.last + QUOTE_REACH).coerceAtMost(text.length - 1)

        // Scanned from the floor rather than from the start of the document, and over
        // the text itself rather than a copy of that window: whether a stop ends a
        // sentence is decided by what follows it, and a window cut just before the term
        // would hide exactly the word — "tj. do dnia" — that the rule turns on.
        val opening = SENTENCE_END.findAll(text, floor)
            .takeWhile { it.range.last < term.first }
            .lastOrNull()
        var start = opening?.let { it.range.last + 1 } ?: floor
        var end = SENTENCE_END.find(text, term.last)
            ?.range?.last?.takeIf { it <= ceiling }
            ?.let { it + 1 }
            ?: (ceiling + 1)

        // The offsets are the citation, so whitespace is dropped by moving them rather
        // than by trimming the text afterwards: a quote that is not exactly what sits
        // between them would make every range in this system mean something slightly
        // different.
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--

        return Citation(start, end)
    }

    /** Half-open, like every other range in this system: `[start, end)`. */
    private data class Citation(val start: Int, val end: Int) {
        fun textIn(text: String): String = text.substring(start, end)
    }

    private class DatePattern(val regex: Regex, val read: (MatchResult) -> LocalDate?)

    private companion object {
        val WHITESPACE = Regex("""\s+""")

        /** Polish names the months in the genitive when it dates something. */
        val MONTHS = listOf(
            "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
            "lipca", "sierpnia", "września", "października", "listopada", "grudnia",
        )

        val LONG_DATE = """(?<day>\d{1,2})\s+(?<month>${MONTHS.joinToString("|")})\s+(?<year>\d{4})"""
        val NUMERIC_DATE = """(?<day>\d{1,2})[.\-/](?<month>\d{1,2})[.\-/](?<year>\d{4})"""

        fun longDateIn(match: MatchResult): LocalDate? = dateOf(
            day = match.groups["day"]?.value,
            month = MONTHS.indexOf(match.groups["month"]?.value?.lowercase()) + 1,
            year = match.groups["year"]?.value,
        )

        fun numericDateIn(match: MatchResult): LocalDate? = dateOf(
            day = match.groups["day"]?.value,
            month = match.groups["month"]?.value?.toIntOrNull() ?: 0,
            year = match.groups["year"]?.value,
        )

        /**
         * Null rather than an exception on the 31st of February. A letter can carry a
         * typo, and a document nobody can read stays undated — never one that stops
         * the pipeline.
         */
        fun dateOf(day: String?, month: Int, year: String?): LocalDate? = runCatching {
            LocalDate.of(year!!.toInt(), month, day!!.toInt())
        }.getOrNull()

        val CLOSING_DATES = listOf(
            DatePattern(Regex("""do\s+dnia\s+$LONG_DATE""", RegexOption.IGNORE_CASE), ::longDateIn),
            DatePattern(Regex("""do\s+dnia\s+$NUMERIC_DATE""", RegexOption.IGNORE_CASE), ::numericDateIn),
            DatePattern(Regex("""do\s+$LONG_DATE""", RegexOption.IGNORE_CASE), ::longDateIn),
            DatePattern(Regex("""do\s+$NUMERIC_DATE""", RegexOption.IGNORE_CASE), ::numericDateIn),
        )

        /** 7, 10, 14, 21 and 30 are the terms these letters actually set. */
        val SPELLED_DAYS = mapOf(
            "siedmiu" to 7,
            "dziesięciu" to 10,
            "czternastu" to 14,
            "dwudziestu jeden" to 21,
            "trzydziestu" to 30,
        )

        val NUMBER = (listOf("""\d{1,3}""") + SPELLED_DAYS.keys.map { it.replace(" ", """\s+""") })
            .joinToString("|")

        val PERIODS = listOf(
            Regex("""w\s+(?:nieprzekraczalnym\s+)?terminie\s+($NUMBER)\s+dni""", RegexOption.IGNORE_CASE),
            Regex("""w\s+ciągu\s+($NUMBER)\s+dni""", RegexOption.IGNORE_CASE),
            Regex("""($NUMBER)[\s-]*dniowym\s+terminie""", RegexOption.IGNORE_CASE),
            Regex("""termin(?:ie)?\s+($NUMBER)\s*dni""", RegexOption.IGNORE_CASE),
        )

        /**
         * A term nobody would set. Three digits are allowed through the pattern so
         * that "w terminie 120 dni" is read whole rather than half-read as 12, and
         * rejected here if it is really a paragraph number that wandered in.
         */
        val PLAUSIBLE_DAYS = 1..180

        /**
         * `dnia` is optional because both forms are current and the longer one is the
         * one a ministry's template prints: "Warszawa, dnia 9 kwietnia 2026 r."
         */
        val DATELINE = Regex(
            """(?:^|\n)[ \t]*\p{Lu}[\p{L}.-]+(?:[ \t]+\p{Lu}?[\p{L}.-]+)?[ \t]*,[ \t]*(?:dnia[ \t]+)?$LONG_DATE""",
        )

        /**
         * What a covering letter does and a statute does not: ask somebody for
         * something. The stems are cut short of their endings because Polish declines
         * all four — uwag, uwagi, uwagami; stanowisko, stanowiska.
         */
        val ASKS_FOR_COMMENTS = Regex("""uwag|stanowisk|opini|konsultacj""", RegexOption.IGNORE_CASE)

        val EMAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

        /**
         * A semicolon or a colon always ends a clause; a full stop does unless a
         * lower-case word follows it, which is what "tj.", "art." and "ust." look like
         * and what the end of a sentence never does.
         */
        val SENTENCE_END = Regex("""[;:]|\.(?!\s*\p{Ll})""")

        /** Enough for the sentence, short enough to render beside a date. */
        const val QUOTE_REACH = 300

        /** A dateline sits above the salutation or it is not a dateline. */
        const val DATELINE_WINDOW = 600

        /** Near enough to the request for comments to be the address for them. */
        const val ADDRESS_WINDOW = 200
    }
}
