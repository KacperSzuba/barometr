package pl.barometr.corpus.internal.structure

import java.text.Normalizer

/**
 * Reads a stretch of a document the way the comparison reads it — and only the
 * comparison.
 *
 * **Nothing here is ever stored.** The archive holds what the source said, and every
 * offset in the system indexes that. This produces a second reading, used to decide
 * whether two units say the same thing, and then thrown away. A normalisation that
 * reached the stored text would break every citation ever made against it.
 *
 * Four things are folded away, each for a reason that comes from the documents
 * themselves:
 *
 * - **The designator**, so that renumbering an article does not read as rewriting it.
 *   That is the whole point of the exercise.
 * - **Case and Unicode form**, because a PDF is not consistent about which composition
 *   of `ł` or `ó` it emits.
 * - **The hyphen a line break left mid-word**, which is a property of the page rather
 *   than a word anybody wrote.
 * - **Whitespace runs**, for the same reason.
 *
 * Punctuation is *not* folded away in [Word.value]: a comma that moved is a change.
 * Whether it is a change worth reporting is a different question, and [Word.core]
 * answers it.
 */
object ComparableText {

    /** The words of `text[from, to)`, with the unit's own designator left out. */
    fun wordsIn(text: String, from: Int, to: Int): List<Word> {
        val span = text.substring(from, to)
        val body = DESIGNATOR.find(span)?.value?.length ?: 0
        val runs = WORD.findAll(span, body).map { it.range.first to (it.range.last + 1) }.toList()

        return joinBrokenWords(span, runs).mapNotNull { (start, end) ->
            wordOf(span, start, end, from)
        }
    }

    /** What two units are compared by: their words, in order, separated by one space. */
    fun comparableOf(words: List<Word>): String = words.joinToString(" ") { it.value }

    /**
     * The same reading with punctuation dropped. Two units whose cores agree while
     * their comparable forms differ changed by a comma, a bracket or a dash — a
     * correction rather than a decision, and the first thing a reader wants filtered
     * out of four hundred changes.
     */
    fun coreOf(words: List<Word>): String =
        words.mapNotNull { it.core.takeIf(String::isNotEmpty) }.joinToString(" ")

    /**
     * `pod-` at the end of a line and `miot` at the start of the next are one word.
     * The joined word keeps the span of both halves, so the citation still covers the
     * characters it was read from.
     */
    private fun joinBrokenWords(span: String, runs: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val joined = mutableListOf<Pair<Int, Int>>()
        var index = 0

        while (index < runs.size) {
            val start = runs[index].first
            var end = runs[index].second
            while (
                span[end - 1] == '-' &&
                index + 1 < runs.size &&
                span.substring(end, runs[index + 1].first).contains('\n')
            ) {
                index++
                end = runs[index].second
            }
            joined += start to end
            index++
        }

        return joined
    }

    private fun wordOf(span: String, start: Int, end: Int, offset: Int): Word? {
        val folded = fold(span.substring(start, end))
        return folded.takeIf { it.isNotEmpty() }?.let {
            Word(
                value = it,
                core = it.filter(Char::isLetterOrDigit),
                charStart = offset + start,
                charEnd = offset + end,
            )
        }
    }

    /** Drops the whitespace and the hyphen a line break left inside a word, then folds case. */
    private fun fold(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFC)
            .replace(BROKEN_WORD, "")
            .filterNot { it == SOFT_HYPHEN }
            .lowercase()

    private const val SOFT_HYPHEN = '\u00AD'

    /**
     * `Art. 12a.`, `§ 3.`, `2.`, `3)`, `b)`, `–` — the label a unit opens with, which
     * belongs to its position rather than to what it says.
     */
    private val DESIGNATOR = Regex(
        """^(Art\.[ \t]*\d+[a-z]?\.?|§[ \t]*\d+[a-z]?\.?|\d+[a-z]?[.)]|[a-ząćęłńóśźż]{1,2}\)|[–—-])[ \t]*""",
    )

    private val WORD = Regex("""\S+""")

    /** The hyphen and the line break it sits on, as they appear inside a joined word. */
    private val BROKEN_WORD = Regex("""-\s*\n\s*""")
}
