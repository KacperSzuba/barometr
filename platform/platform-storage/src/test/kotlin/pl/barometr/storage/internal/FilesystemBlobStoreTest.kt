package pl.barometr.storage.internal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.shared.ContentHash
import pl.barometr.storage.BlobBucket
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilesystemBlobStoreTest {

    @TempDir
    lateinit var root: Path

    private val store: FilesystemBlobStore by lazy { FilesystemBlobStore(StorageProperties(root)) }

    @Test
    fun `stores content under its hash and reads it back`() {
        val payload = "treść ustawy".toByteArray()

        val stored = store.store(BlobBucket.RAW, payload, "text/plain")

        assertEquals(ContentHash.of(payload), stored.contentHash)
        assertFalse(stored.deduplicated)
        assertEquals(payload.size.toLong(), stored.byteSize)
        assertTrue(store.exists(BlobBucket.RAW, stored.contentHash))
        assertEquals(
            "treść ustawy",
            store.read(BlobBucket.RAW, stored.contentHash)!!.use { String(it.readAllBytes()) },
        )
    }

    /**
     * The property the whole ingestion pipeline leans on: the same PDF reached
     * from two different sources costs storage once, and a retried connector run
     * writes nothing the second time.
     */
    @Test
    fun `identical content stores once`() {
        val payload = "ten sam PDF".toByteArray()

        val first = store.store(BlobBucket.RAW, payload, "application/pdf")
        val second = store.store(BlobBucket.RAW, payload.copyOf(), "application/pdf")

        assertFalse(first.deduplicated)
        assertTrue(second.deduplicated)
        assertEquals(first.contentHash, second.contentHash)

        val objects = Files.walk(root).filter { Files.isRegularFile(it) }.count()
        assertEquals(1, objects, "the same bytes must not be written twice")
    }

    @Test
    fun `buckets are isolated`() {
        val payload = "wspólna treść".toByteArray()
        val stored = store.store(BlobBucket.RAW, payload, "text/plain")

        assertTrue(store.exists(BlobBucket.RAW, stored.contentHash))
        assertFalse(store.exists(BlobBucket.DERIVED, stored.contentHash))
    }

    @Test
    fun `reading an unknown hash returns null rather than failing`() {
        val absent = ContentHash.of("nigdy nie zapisane".toByteArray())
        assertNull(store.read(BlobBucket.RAW, absent))
    }

    /**
     * Sharding keeps a bucket from becoming one directory with millions of
     * entries, which every filesystem handles badly.
     */
    @Test
    fun `keys are sharded on the leading hex pairs`() {
        val hash = ContentHash.of("cokolwiek".toByteArray())
        val hex = hash.hex

        assertEquals("${hex.substring(0, 2)}/${hex.substring(2, 4)}/$hex", store.keyOf(hash))
    }

    @Test
    fun `no staging files are left behind`() {
        store.store(BlobBucket.RAW, "a".toByteArray(), "text/plain")
        store.store(BlobBucket.RAW, "b".toByteArray(), "text/plain")

        val staging = Files.walk(root).filter { it.fileName.toString().startsWith(".staging-") }.count()
        assertEquals(0, staging)
    }
}
