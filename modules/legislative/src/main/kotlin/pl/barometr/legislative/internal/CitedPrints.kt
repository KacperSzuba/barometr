package pl.barometr.legislative.internal

/**
 * The prints an agenda item names, read out of the item's own words.
 *
 * This is the only join there is between a vote and the bill it decides. The Sejm's
 * voting record carries no print field: what it carries is a title written for a person
 * — "Pkt. 3 Wybór Wicemarszałków Sejmu RP (druki nr 3, 4, 5, 6, 7 i 8)" — and the
 * numbers in the brackets are the whole of the link.
 *
 * A citation is matched as a list of numbers rather than as text running to a closing
 * bracket, and that is the difference between reading the citation and reading whatever
 * followed it: a title that names a print and then goes on in prose would otherwise have
 * its years, its article numbers and its amounts swept up as prints as well.
 */
object CitedPrints {

    /** `424`, and the lettered revision the Sejm issues when a print is corrected: `424-A`. */
    private const val NUMBER = """\d+(?:-[A-Za-z])?"""

    /** `, ` between all but the last, ` i ` before it — which is how the register writes a list. */
    private const val SEPARATOR = """(?:\s*,\s*|\s+(?:i|oraz)\s+)"""

    private const val NUMBERS = """$NUMBER(?:$SEPARATOR$NUMBER)*"""

    /** `druk nr 424`, `druki nr 1 i 2`, `drukach nr 3, 4 i 5`. */
    private val CITATION = Regex("""druk(?:i|u|ach|ami|ów|iem)?\s*(?:nr\.?\s*)?($NUMBERS)""", RegexOption.IGNORE_CASE)

    private val ONE_NUMBER = Regex(NUMBER)

    /**
     * Every print the title cites, as the address the archive keeps that print under.
     *
     * The address rather than the number, because that is the string `draft_identifier`
     * holds under `druk_sejmowy`: returning `424` would leave every caller to build
     * `term10/print/424` for itself, and one of them to build it differently.
     *
     * In the order the title names them, without repeats — a title that cites one print
     * twice cites one print.
     */
    fun addressesIn(title: String, term: Int): List<String> =
        CITATION.findAll(title)
            .flatMap { citation -> ONE_NUMBER.findAll(citation.groupValues[1]) }
            .map { number -> SejmPrintAddress.of(term, number.value) }
            .distinct()
            .toList()
}
