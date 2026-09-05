package pl.barometr.corpus.internal.diff

import com.github.difflib.DiffUtils
import com.github.difflib.patch.Chunk
import com.github.difflib.patch.DeltaType
import org.springframework.stereotype.Component
import pl.barometr.corpus.api.ChangeKind
import pl.barometr.corpus.api.WordChange
import pl.barometr.corpus.internal.structure.Word

/**
 * Which words inside a modified unit changed, addressed on both sides.
 *
 * The unit level answers "this paragraph changed"; this answers "the term is thirty
 * days rather than fourteen", which is the sentence a reader came for. It runs only on
 * units alignment has already paired, so the expensive part of comparing two documents
 * has been done before this sees anything.
 *
 * The alignment of two word lists is a solved problem and is
 * [DiffUtils]' — a Myers diff over any list. What is ours is which words those are:
 * the folded readings from [pl.barometr.corpus.internal.structure.ComparableText], so
 * that a word broken by a line break, or written in a different Unicode composition,
 * does not read as a change nobody made. The offsets carried by each word are what
 * turn a delta back into a citation.
 */
@Component
class WordLevelChanges(private val properties: DiffProperties) {

    fun changesWithin(before: UnitReading, after: UnitReading): WordChanges {
        val deltas = DiffUtils.diff(before.words.map(Word::value), after.words.map(Word::value)).deltas

        if (deltas.size > properties.maxWordChanges) {
            return WordChanges(listOf(wholeUnit(before, after)), truncated = true)
        }

        val changes = deltas.mapNotNull { delta ->
            changeOf(delta.type, delta.source, delta.target, before, after)
        }

        return WordChanges(changes, truncated = false)
    }

    private fun changeOf(
        type: DeltaType,
        source: Chunk<String>,
        target: Chunk<String>,
        before: UnitReading,
        after: UnitReading,
    ): WordChange? {
        val kind = when (type) {
            DeltaType.INSERT -> ChangeKind.ADDED
            DeltaType.DELETE -> ChangeKind.REMOVED
            DeltaType.CHANGE -> ChangeKind.MODIFIED
            // Equal runs are the document, not a change. The library reports them only
            // when asked to; this pass ignores them either way.
            DeltaType.EQUAL -> return null
        }

        val older = spanOf(before.words, source.position, source.size())
        val newer = spanOf(after.words, target.position, target.size())

        return WordChange(
            kind = kind,
            fromCharStart = older?.first,
            fromCharEnd = older?.second,
            toCharStart = newer?.first,
            toCharEnd = newer?.second,
        )
    }

    /** The run of [count] words starting at [from], as one range, or null where nothing was touched. */
    private fun spanOf(words: List<Word>, from: Int, count: Int): Pair<Int, Int>? =
        words.takeIf { count > 0 && from < it.size }
            ?.let { it[from].charStart to it[minOf(from + count, it.size) - 1].charEnd }

    /** The unit against the unit: what is left to say when everything inside it changed. */
    private fun wholeUnit(before: UnitReading, after: UnitReading) = WordChange(
        kind = ChangeKind.MODIFIED,
        fromCharStart = before.unit.charStart,
        fromCharEnd = before.unit.charEnd,
        toCharStart = after.unit.charStart,
        toCharEnd = after.unit.charEnd,
    )
}
