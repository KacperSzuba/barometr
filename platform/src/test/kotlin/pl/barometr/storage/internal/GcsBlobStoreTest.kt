package pl.barometr.storage.internal

import com.google.cloud.NoCredentials
import com.google.cloud.storage.StorageOptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import pl.barometr.testing.ObjectStorageTestServer
import kotlin.test.assertTrue

/**
 * The same contract, against a server that speaks Google Cloud Storage.
 *
 * The buckets are prepared the way the application prepares them — at startup, once —
 * because "does it come up against an empty project" is part of what is being asked.
 */
class GcsBlobStoreTest : BlobStoreContract() {

    override val store: BlobStore get() = shared

    /**
     * Every restart runs this, and a second start must not be an error — nor must two
     * instances starting at once, where exactly one of them creates each bucket.
     */
    @Test
    fun `preparing the buckets again is not an error`() {
        shared.ensureBuckets()
        shared.ensureBuckets()

        val stored = store.store(BlobBucket.EXPORTS, payload("po restarcie"), "text/plain")
        assertTrue(store.exists(BlobBucket.EXPORTS, stored.contentHash))
    }

    companion object {
        private lateinit var shared: GcsBlobStore

        @JvmStatic
        @BeforeAll
        fun connect() {
            val storage = StorageOptions.newBuilder()
                .setProjectId("barometr-test")
                .setHost(ObjectStorageTestServer.endpoint)
                .setCredentials(NoCredentials.getInstance())
                .build()
                .service

            shared = GcsBlobStore(storage, "barometr", "europe-central2").also { it.ensureBuckets() }
        }
    }
}
