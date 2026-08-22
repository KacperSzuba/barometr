package pl.barometr.storage.internal

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/** The contract, plus what only a filesystem can be asked. */
class FilesystemBlobStoreTest : BlobStoreContract() {

    @TempDir
    lateinit var root: Path

    override val store: BlobStore get() = FilesystemBlobStore(root)

    @Test
    fun `identical content is one file on disk`() {
        val payload = payload("ten sam PDF")

        store.store(BlobBucket.RAW, payload, "application/pdf")
        store.store(BlobBucket.RAW, payload.copyOf(), "application/pdf")

        val objects = Files.walk(root).filter { Files.isRegularFile(it) }.count()
        assertEquals(1, objects, "the same bytes must not be written twice")
    }

    /**
     * A write goes to a staging file and is moved into place, so a crash mid-write
     * cannot leave a truncated object at an address claiming to be a complete one.
     * What must not happen is the staging files accumulating.
     */
    @Test
    fun `no staging files are left behind`() {
        store.store(BlobBucket.RAW, payload("a"), "text/plain")
        store.store(BlobBucket.RAW, payload("b"), "text/plain")

        val staging = Files.walk(root).filter { it.fileName.toString().startsWith(".staging-") }.count()
        assertEquals(0, staging)
    }
}
