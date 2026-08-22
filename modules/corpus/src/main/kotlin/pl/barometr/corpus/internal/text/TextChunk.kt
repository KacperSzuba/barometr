package pl.barometr.corpus.internal.text

/**
 * A stretch of a document's text, addressed the way every claim in this system
 * addresses one.
 *
 * [content] is always `text.substring(charStart, charEnd)` of the text the chunk was
 * cut from — not a cleaned-up version of it. The moment those two diverge, a summary
 * sentence citing a range highlights the wrong words in the original, and the
 * provenance this product rests on becomes a claim nobody can check.
 */
data class TextChunk(
    val ordinal: Int,
    val charStart: Int,
    val charEnd: Int,
    val content: String,
)
