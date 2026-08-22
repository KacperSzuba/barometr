package pl.barometr.corpus.internal.text

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chunker on its own: no database, no parser, no container.
 *
 * Everything here is really one property stated from several angles — a chunk's range
 * indexes the text it was cut from. It is worth this much attention because it is the
 * claim every later feature rests on: a summary sentence, a diff, an embedding all
 * cite a range, and a range that is off by one highlights the wrong sentence in a bill.
 */
class TextChunkerTest {

    @Test
    fun `every chunk's range indexes the text it was cut from`() {
        val text = statute()

        TextChunker(maxChars = 200).chunk(text).forEach { chunk ->
            assertEquals(
                chunk.content,
                text.substring(chunk.charStart, chunk.charEnd),
                "chunk ${chunk.ordinal} does not sit where it says it does",
            )
        }
    }

    @Test
    fun `chunks are numbered from one and in the order they appear`() {
        val chunks = TextChunker(maxChars = 200).chunk(statute())

        assertEquals((1..chunks.size).toList(), chunks.map { it.ordinal })
        assertTrue(chunks.zipWithNext().all { (before, after) -> before.charEnd <= after.charStart })
    }

    /**
     * No overlap, deliberately. Overlapping windows are the usual answer for
     * retrieval and the wrong one here: two chunks quoting the same sentence give a
     * citation two addresses, and the promise is that a sentence has one.
     */
    @Test
    fun `no character belongs to two chunks`() {
        val chunks = TextChunker(maxChars = 120).chunk(statute())

        assertTrue(chunks.zipWithNext().none { (before, after) -> after.charStart < before.charEnd })
    }

    @Test
    fun `paragraphs small enough to share a chunk are kept together`() {
        val text = "Pierwszy akapit.\n\nDrugi akapit.\n\nTrzeci akapit."

        val chunks = TextChunker(maxChars = 1_000).chunk(text)

        assertEquals(1, chunks.size)
        assertEquals(text, chunks.single().content)
    }

    @Test
    fun `a paragraph that would overflow a chunk starts a new one`() {
        val text = "Art. 1. Pierwszy.\n\nArt. 2. Drugi.\n\nArt. 3. Trzeci."

        val chunks = TextChunker(maxChars = 35).chunk(text)

        assertEquals(2, chunks.size)
        assertEquals("Art. 1. Pierwszy.\n\nArt. 2. Drugi.", chunks[0].content)
        assertEquals("Art. 3. Trzeci.", chunks[1].content)
    }

    /**
     * A single paragraph longer than a whole chunk has to be cut somewhere, and the
     * cut falls on a space so that a chunk does not end mid-word.
     */
    @Test
    fun `a paragraph longer than a chunk is cut at a word boundary`() {
        val text = "alfa beta gamma delta epsilon dzeta eta theta"

        val chunks = TextChunker(maxChars = 20).chunk(text)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.content.length <= 20 })
        assertTrue(chunks.none { it.content.startsWith(" ") || it.content.endsWith(" ") })
        assertEquals(text.replace(" ", ""), chunks.joinToString("") { it.content }.replace(" ", ""))
    }

    /**
     * A run with nothing to cut on — a table rendered without spaces, an encoded
     * attachment — is cut at the limit anyway. The alternative is a chunk of
     * unbounded size, which is worse than a word split in half.
     */
    @Test
    fun `an unbroken run longer than a chunk is cut at the limit`() {
        val text = "x".repeat(25)

        val chunks = TextChunker(maxChars = 10).chunk(text)

        assertEquals(listOf(10, 10, 5), chunks.map { it.content.length })
    }

    /**
     * Whitespace is excluded from a chunk by moving its bounds, never by trimming its
     * content — trimming is exactly how content and range start to disagree.
     */
    @Test
    fun `blank space between paragraphs belongs to no chunk`() {
        val text = "\n\n   Pierwszy.\n\n\n\n   Drugi.   \n\n"

        val chunks = TextChunker(maxChars = 12).chunk(text)

        assertEquals(listOf("Pierwszy.", "Drugi."), chunks.map { it.content })
        chunks.forEach { assertEquals(it.content, text.substring(it.charStart, it.charEnd)) }
    }

    @Test
    fun `text with nothing in it produces no chunks`() {
        assertEquals(emptyList(), TextChunker().chunk("   \n\n \t \n "))
        assertEquals(emptyList(), TextChunker().chunk(""))
    }

    private fun statute(): String = (1..40).joinToString("\n\n") { article ->
        "Art. $article. " + "Kto narusza przepisy niniejszej ustawy, podlega karze. ".repeat(article % 5 + 1)
    }
}
