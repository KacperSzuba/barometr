package pl.barometr.corpus.internal.text

import org.springframework.stereotype.Component

/**
 * Cuts a document's text into the pieces everything downstream works in.
 *
 * Three decisions, all of them about provenance rather than about tidiness.
 *
 * **Cuts fall on paragraph boundaries.** A legal document's meaning survives being
 * split between articles and does not survive being split mid-sentence, and a blank
 * line is the only structural signal plain text still carries. Parsing the redactional
 * units properly — article, paragraph, point — is the diff work, and this must not
 * pretend to have done it.
 *
 * **No overlap.** Overlapping windows are the usual answer for retrieval, and they are
 * the wrong answer here: two chunks quoting the same sentence give a citation two
 * addresses, and the whole promise is that a sentence has one.
 *
 * **Offsets are exact.** Every range indexes the string passed in, so leading and
 * trailing whitespace is excluded from a chunk by moving its bounds rather than by
 * trimming its content.
 */
@Component
class TextChunker(private val maxChars: Int = DEFAULT_MAX_CHARS) {

    init {
        require(maxChars > 0) { "A chunk must be allowed at least one character" }
    }

    fun chunk(text: String): List<TextChunk> {
        val pieces = paragraphsIn(text).flatMap { paragraph ->
            if (paragraph.length <= maxChars) listOf(paragraph) else divide(text, paragraph)
        }

        val chunks = mutableListOf<TextChunk>()
        var open: Span? = null

        pieces.forEach { piece ->
            val extended = open?.let { Span(it.start, piece.end) }
            open = when {
                extended == null -> piece
                extended.length <= maxChars -> extended
                else -> {
                    chunks += chunkOf(text, chunks.size, open!!)
                    piece
                }
            }
        }
        open?.let { chunks += chunkOf(text, chunks.size, it) }

        return chunks
    }

    private fun chunkOf(text: String, ordinal: Int, span: Span) = TextChunk(
        // Numbered from one: `ordinal` is what a citation quotes, and a reader
        // counting paragraphs does not start at zero.
        ordinal = ordinal + 1,
        charStart = span.start,
        charEnd = span.end,
        content = text.substring(span.start, span.end),
    )

    /** The stretches between blank lines, with their surrounding whitespace dropped. */
    private fun paragraphsIn(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var cursor = 0

        PARAGRAPH_BREAK.findAll(text).forEach { br ->
            spanOf(text, cursor, br.range.first)?.let { spans += it }
            cursor = br.range.last + 1
        }
        spanOf(text, cursor, text.length)?.let { spans += it }

        return spans
    }

    /**
     * One paragraph longer than a whole chunk, cut at the last space before each
     * limit so that a chunk does not end mid-word. A single unbroken run longer than
     * the limit — a table rendered without spaces, a base64 blob — is cut at the
     * limit, because the alternative is a chunk of unbounded size.
     */
    private fun divide(text: String, paragraph: Span): List<Span> {
        val pieces = mutableListOf<Span>()
        var start = paragraph.start

        while (paragraph.end - start > maxChars) {
            val limit = start + maxChars
            var cut = limit
            while (cut > start && !text[cut - 1].isWhitespace()) cut--
            if (cut <= start) cut = limit

            spanOf(text, start, cut)?.let { pieces += it }
            start = cut
        }
        spanOf(text, start, paragraph.end)?.let { pieces += it }

        return pieces
    }

    /** Null for a stretch that is nothing but whitespace, which is not a chunk. */
    private fun spanOf(text: String, from: Int, to: Int): Span? {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return if (end > start) Span(start, end) else null
    }

    private data class Span(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    companion object {
        /**
         * Fifteen hundred characters: a few paragraphs of statute, and comfortably
         * inside the input window of the multilingual embedding models this corpus is
         * headed for. Changing it re-chunks nothing on its own — existing rows keep
         * the ranges they were cut with, which is the point of storing them.
         */
        const val DEFAULT_MAX_CHARS = 1_500

        private val PARAGRAPH_BREAK = Regex("""\n[ \t\r]*\n""")
    }
}
