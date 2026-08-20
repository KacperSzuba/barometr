package pl.barometr.shared

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The system's universal content address, and therefore the thing every claim about
 * deduplication rests on: the storage key, the ingestion idempotency key and the
 * identity of a document version are all this value.
 *
 * Untested until now, which is the kind of gap that only looks harmless — a
 * `ContentHash` that compared wrongly would not fail here, it would quietly double
 * the archive.
 */
class ContentHashTest {

    /** Fixed vectors, so a change of algorithm cannot pass as a refactor. */
    @Test
    fun `hashes match the published SHA-256 of the same bytes`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ContentHash.of("abc".toByteArray()).hex,
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ContentHash.of(ByteArray(0)).hex,
        )
    }

    /**
     * The property the whole design depends on: two connectors that fetch the same
     * PDF independently must arrive at the same address, with no coordination.
     * A value class wrapping a `ByteArray` would fail this, because the array's
     * `equals` compares references.
     */
    @Test
    fun `the same content hashes to the same value, whoever produced it`() {
        val payload = "identyczny PDF".toByteArray()

        assertEquals(ContentHash.of(payload), ContentHash.of(payload.copyOf()))
        assertEquals(ContentHash.of(payload).hashCode(), ContentHash.of(payload.copyOf()).hashCode())
    }

    @Test
    fun `different content hashes differently`() {
        assertNotEquals(ContentHash.of("wersja 1".toByteArray()), ContentHash.of("wersja 2".toByteArray()))
    }

    /** Hex for logs, keys and URLs; bytes for the `bytea` column. Both ways, losslessly. */
    @Test
    fun `hex and bytes round-trip back to the same hash`() {
        val hash = ContentHash.of("ustawa".toByteArray())

        assertEquals(hash, ContentHash.parse(hash.hex))
        assertEquals(hash, ContentHash.ofBytes(hash.bytes))
        assertEquals(ContentHash.BYTE_LENGTH, hash.bytes.size)
    }

    @Test
    fun `hex is normalised, so case cannot split one address into two`() {
        val hash = ContentHash.of("ustawa".toByteArray())

        assertEquals(hash, ContentHash.parse(hash.hex.uppercase()))
    }

    @Test
    fun `a string that is not a SHA-256 is refused`() {
        val tooShort = assertFailsWith<IllegalArgumentException> { ContentHash.parse("abc") }
        assertTrue(tooShort.message!!.contains("Not a SHA-256"))

        assertFailsWith<IllegalArgumentException> { ContentHash.parse("z".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { ContentHash.parse("") }
    }

    @Test
    fun `a byte array of the wrong length is refused`() {
        val failure = assertFailsWith<IllegalArgumentException> { ContentHash.ofBytes(ByteArray(16)) }
        assertTrue(failure.message!!.contains("32 bytes"))
    }
}
