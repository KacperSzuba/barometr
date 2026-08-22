package pl.barometr.storage.internal

import org.junit.jupiter.api.Test
import pl.barometr.shared.ContentHash
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a blob store promises, asked of every implementation there is.
 *
 * The archive is the product: everything else in this system is derived from it and can
 * be rebuilt, and it cannot. So the guarantees are written once and both stores answer
 * them — a disk and an S3 endpoint behaving differently under the same call is exactly
 * the defect nobody would find until a document was missing.
 */
abstract class BlobStoreContract {

    protected abstract val store: BlobStore

    /** Distinct per run, so a shared object store carries no memory of the last one. */
    protected fun payload(text: String): ByteArray = "$text ${System.nanoTime()}".toByteArray()

    @Test
    fun `stores content under its hash and reads it back`() {
        val payload = payload("treść ustawy")

        val stored = store.store(BlobBucket.RAW, payload, "text/plain")

        assertEquals(ContentHash.of(payload), stored.contentHash)
        assertFalse(stored.deduplicated)
        assertEquals(payload.size.toLong(), stored.byteSize)
        assertTrue(store.exists(BlobBucket.RAW, stored.contentHash))
        assertEquals(
            String(payload),
            store.read(BlobBucket.RAW, stored.contentHash)!!.use { String(it.readAllBytes()) },
        )
    }

    /**
     * The property the whole ingestion pipeline leans on, and the specification's own
     * acceptance criterion: the same PDF reached from two sources costs storage once,
     * and a retried connector run writes nothing the second time.
     */
    @Test
    fun `identical content stores once`() {
        val payload = payload("ten sam PDF")

        val first = store.store(BlobBucket.RAW, payload, "application/pdf")
        val second = store.store(BlobBucket.RAW, payload.copyOf(), "application/pdf")

        assertFalse(first.deduplicated)
        assertTrue(second.deduplicated, "the second write must recognise the bytes")
        assertEquals(first.contentHash, second.contentHash)
    }

    @Test
    fun `buckets are isolated`() {
        val stored = store.store(BlobBucket.RAW, payload("wspólna treść"), "text/plain")

        assertTrue(store.exists(BlobBucket.RAW, stored.contentHash))
        assertFalse(store.exists(BlobBucket.DERIVED, stored.contentHash))
    }

    @Test
    fun `reading an unknown hash returns null rather than failing`() {
        assertNull(store.read(BlobBucket.RAW, ContentHash.of(payload("nigdy nie zapisane"))))
    }

    /**
     * Sharding keeps a bucket from becoming one directory — or one prefix — with
     * millions of entries, which filesystems and object-storage consoles both handle
     * badly.
     */
    @Test
    fun `keys are sharded on the leading hex pairs`() {
        val hash = ContentHash.of(payload("cokolwiek"))
        val hex = hash.hex

        assertEquals("${hex.substring(0, 2)}/${hex.substring(2, 4)}/$hex", store.keyOf(hash))
    }

    @Test
    fun `content survives the round trip byte for byte`() {
        // Bytes that are not text: an S3 client that decided to be helpful about
        // encoding would corrupt exactly this and nothing else.
        val payload = ByteArray(512) { (it % 256).toByte() }

        val stored = store.store(BlobBucket.DERIVED, payload, "application/octet-stream")

        assertTrue(
            payload.contentEquals(store.read(BlobBucket.DERIVED, stored.contentHash)!!.use { it.readAllBytes() }),
        )
    }
}
