package pl.barometr.corpus.internal.structure

import org.springframework.stereotype.Component

/**
 * Reads the editorial units out of a legal document's extracted text.
 *
 * The step that decides whether a diff is worth reading. Compared as flat text, a bill
 * whose article 5 became article 6 reports every line of it as deleted and re-added;
 * compared as units, it reports one renumbering. Everything downstream — alignment,
 * word-level changes, the "only what matters" filter — rests on this pass, and nothing
 * downstream can recover from it getting the units wrong.
 *
 * **One linear pass over lines, with a stack.** A unit belongs under the nearest open
 * unit of a lower rank, which is [UnitKind.level] and nothing else. There is no
 * grammar and no lookahead: legal drafting puts every designator at the start of its
 * own line, and that is the only signal plain text still carries after a PDF has been
 * through it.
 *
 * **A designator is taken only where it continues its siblings.** A wrapped line
 * beginning `30. dnia` is not paragraph thirty, and in a three-hundred-page document
 * there are hundreds of such lines. So a numbered opening is accepted when it is the
 * first of its kind under its parent or the one that follows the previous — which is
 * how statutes are numbered, including the `2a` an amendment inserts.
 *
 * **A unit spans its own words only.** Its children follow it in the list with their
 * own spans; the article's full extent is whatever its path covers. Offsets index the
 * string passed in, exactly, so a citation rendered from a change is what the archive
 * holds.
 *
 * Stateless and thread-safe: everything the pass needs lives on the stack of the call.
 */
@Component
class EditorialUnitReader {

    fun unitsIn(text: String): List<EditorialUnit> {
        val openings = openingsIn(text)
        if (openings.isEmpty()) {
            return listOfNotNull(preambleOf(text, text.length))
        }

        val units = mutableListOf<EditorialUnit>()
        preambleOf(text, openings.first().at)?.let { units += it }

        openings.forEachIndexed { index, opening ->
            val until = openings.getOrNull(index + 1)?.at ?: text.length
            spanOf(text, opening.at, until)?.let { (start, end) ->
                units += EditorialUnit(opening.kind, opening.path, opening.designator, start, end)
            }
        }

        return units
    }

    /** The title and whatever precedes the first numbered unit, which is text like any other. */
    private fun preambleOf(text: String, until: Int): EditorialUnit? =
        spanOf(text, 0, until)?.let { (start, end) ->
            EditorialUnit(UnitKind.PREAMBLE, UnitPath.root(UnitKind.PREAMBLE, null), null, start, end)
        }

    private fun openingsIn(text: String): List<Opening> {
        val openings = mutableListOf<Opening>()
        val open = ArrayDeque<Opening>()
        // The last designator closed under a given parent, per kind: what a candidate
        // has to continue in order to be believed.
        val siblings = mutableMapOf<Pair<String, UnitKind>, String>()
        var lineStart = 0

        while (lineStart < text.length) {
            val lineEnd = text.indexOf('\n', lineStart).takeIf { it >= 0 } ?: text.length
            candidatesOn(text.substring(lineStart, lineEnd)).forEach { candidate ->
                admit(candidate, lineStart + candidate.at, open, siblings)?.let { openings += it }
            }
            lineStart = lineEnd + 1
        }

        return openings
    }

    /**
     * Places a candidate under the units still open, or refuses it.
     *
     * Refusing is the interesting half: a number at the start of a wrapped line looks
     * exactly like a paragraph opening, and the only thing that tells them apart is
     * whether the numbering continues.
     */
    private fun admit(
        candidate: Candidate,
        at: Int,
        open: ArrayDeque<Opening>,
        siblings: MutableMap<Pair<String, UnitKind>, String>,
    ): Opening? {
        val closed = mutableListOf<Opening>()
        while (open.isNotEmpty() && open.last().kind.level >= candidate.kind.level) {
            closed += open.removeLast()
        }

        val parent = open.lastOrNull()
        val under = parent?.path?.value ?: ""
        val previous = siblings[under to candidate.kind]

        val designator = candidate.designator ?: nextTiret(previous)
        if (!continues(candidate.kind, previous, designator)) {
            // Put back what was closed on the strength of a line that turns out to be
            // prose: the units it would have ended are still open.
            closed.asReversed().forEach(open::addLast)
            return null
        }

        // A closed unit takes its children's numbering with it: the points under
        // article 5 must not tell article 6 what number its first point may have.
        closed.forEach { ended ->
            siblings.keys.removeAll { (parent, _) -> parent.isNotEmpty() && ended.path.covers(UnitPath(parent)) }
        }
        siblings[under to candidate.kind] = designator

        val opening = Opening(
            kind = candidate.kind,
            designator = designator,
            path = parent?.path?.child(candidate.kind, designator)
                ?: UnitPath.root(candidate.kind, designator),
            at = at,
        )
        open.addLast(opening)
        return opening
    }

    /**
     * Whether this designator follows the previous one of its kind under the same
     * parent.
     *
     * Divisions are taken on their word — `Rozdział`, `Dział` are spelled out and
     * nothing else begins a line that way. Articles are allowed to move forward by
     * more than one, because an amendment repeals whole articles and the numbering
     * closes over them. The numbered units inside an article are held to the tightest
     * rule, because they are the ones a wrapped line imitates.
     */
    private fun continues(kind: UnitKind, previous: String?, designator: String): Boolean = when {
        kind.isDivision || kind == UnitKind.TIRET -> true

        kind == UnitKind.ARTYKUL || kind == UnitKind.PARAGRAF ->
            previous == null || numberIn(designator) >= numberIn(previous)

        kind == UnitKind.LITERA -> when (previous) {
            null -> designator == FIRST_LETTER
            else -> designator == letterAfter(previous) || designator == previous + FIRST_LETTER
        }

        // USTEP and PUNKT: the first is 1, and every other is the previous number or
        // the one after it — `2a` follows `2`, `3` follows `2a`.
        else -> when (previous) {
            null -> numberIn(designator) == 1
            else -> numberIn(designator) in numberIn(previous)..(numberIn(previous) + 1)
        }
    }

    /**
     * The designators this line opens, in the order they are written.
     *
     * Usually one. Legal drafting also writes the first paragraph on the article's own
     * line — `Art. 5. 1. Przedsiębiorca…` — and that is two units, not one with a
     * number in it. Each designator after the first has to open something deeper than
     * the one before it, which is what keeps a row of numbers left by a table from
     * becoming a row of paragraphs.
     */
    private fun candidatesOn(line: String): List<Candidate> {
        if (line.isBlank()) return emptyList()

        val candidates = mutableListOf<Candidate>()
        var at = 0

        while (at < line.length) {
            val candidate = designatorAt(line, at)?.takeIf { found ->
                candidates.isEmpty() || found.kind.level > candidates.last().kind.level
            } ?: break

            candidates += candidate
            at = candidate.until
        }

        return candidates
    }

    private fun designatorAt(line: String, at: Int): Candidate? {
        DIVISIONS.forEach { (pattern, kind) ->
            pattern.matchAt(line, at)?.let { return Candidate(kind, it.groupValues[1].lowercase(), at, it.range.last + 1) }
        }
        ARTICLE.matchAt(line, at)?.let { return Candidate(UnitKind.ARTYKUL, it.groupValues[1].lowercase(), at, it.range.last + 1) }
        PARAGRAPH.matchAt(line, at)?.let { return Candidate(UnitKind.PARAGRAF, it.groupValues[1].lowercase(), at, it.range.last + 1) }
        POINT.matchAt(line, at)?.let { return Candidate(UnitKind.PUNKT, it.groupValues[1].lowercase(), at, it.range.last + 1) }
        LETTER.matchAt(line, at)?.let { return Candidate(UnitKind.LITERA, it.groupValues[1].lowercase(), at, it.range.last + 1) }
        SUBSECTION.matchAt(line, at)?.let { return Candidate(UnitKind.USTEP, it.groupValues[1].lowercase(), at, it.range.last + 1) }
        DASH.matchAt(line, at)?.let { return Candidate(UnitKind.TIRET, null, at, it.range.last + 1) }

        return null
    }

    /** Tirets carry no designator of their own, so they are counted where they sit. */
    private fun nextTiret(previous: String?): String = ((previous?.toIntOrNull() ?: 0) + 1).toString()

    private fun numberIn(designator: String): Int = designator.takeWhile { it.isDigit() }.toIntOrNull() ?: 0

    private fun letterAfter(previous: String): String =
        previous.dropLast(1) + (previous.last() + 1)

    /** The stretch from [from] to [to] with its surrounding whitespace dropped, or null when there is none. */
    private fun spanOf(text: String, from: Int, to: Int): Pair<Int, Int>? {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (end > start) start to end else null
    }

    /** A designator found on a line: what it opens, and where it starts and stops on that line. */
    private data class Candidate(val kind: UnitKind, val designator: String?, val at: Int, val until: Int)

    private data class Opening(
        val kind: UnitKind,
        val designator: String,
        val path: UnitPath,
        val at: Int,
    )

    private companion object {
        const val FIRST_LETTER = "a"

        /**
         * Every pattern is anchored at the start of the line, after whatever indentation
         * the extractor left, and every one of them requires what follows the
         * designator to be there. `12a` is an article an amendment inserted; `III` and
         * `2` are both ways a division is numbered.
         */
        val ARTICLE = Regex("""[ \t]*Art\.[ \t]*(\d+[a-z]?)\.?(?=[ \t]|$)""")
        val PARAGRAPH = Regex("""[ \t]*§[ \t]*(\d+[a-z]?)\.?(?=[ \t]|$)""")
        val SUBSECTION = Regex("""[ \t]*(\d+[a-z]?)\.[ \t]+(?=\S)""")
        val POINT = Regex("""[ \t]*(\d+[a-z]?)\)[ \t]+(?=\S)""")
        val LETTER = Regex("""[ \t]*([a-ząćęłńóśźż]{1,2})\)[ \t]+(?=\S)""")

        /**
         * A tiret: an en dash, an em dash or a hyphen opening a line. The lookahead for
         * a following word is what keeps a page of a table drawn in dashes from
         * becoming three hundred units.
         */
        val DASH = Regex("""[ \t]*[–—-][ \t]+(?=\S)""")

        val DIVISIONS = listOf(
            Regex("""[ \t]*CZĘŚĆ[ \t]+([IVXLCDM]+|\d+[a-z]?)\b""", RegexOption.IGNORE_CASE) to UnitKind.CZESC,
            Regex("""[ \t]*KSIĘGA[ \t]+([IVXLCDM]+|\d+[a-z]?)\b""", RegexOption.IGNORE_CASE) to UnitKind.KSIEGA,
            Regex("""[ \t]*TYTUŁ[ \t]+([IVXLCDM]+|\d+[a-z]?)\b""", RegexOption.IGNORE_CASE) to UnitKind.TYTUL,
            Regex("""[ \t]*DZIAŁ[ \t]+([IVXLCDM]+|\d+[a-z]?)\b""", RegexOption.IGNORE_CASE) to UnitKind.DZIAL,
            Regex("""[ \t]*ROZDZIAŁ[ \t]+([IVXLCDM]+|\d+[a-z]?)\b""", RegexOption.IGNORE_CASE) to UnitKind.ROZDZIAL,
            Regex("""[ \t]*ODDZIAŁ[ \t]+([IVXLCDM]+|\d+[a-z]?)\b""", RegexOption.IGNORE_CASE) to UnitKind.ODDZIAL,
        )
    }
}
