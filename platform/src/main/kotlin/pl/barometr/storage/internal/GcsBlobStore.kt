package pl.barometr.storage.internal

import com.google.cloud.storage.BlobId
import com.google.cloud.storage.BlobInfo
import com.google.cloud.storage.BucketInfo
import com.google.cloud.storage.Storage
import com.google.cloud.storage.StorageException
import org.slf4j.LoggerFactory
import pl.barometr.shared.ContentHash
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import pl.barometr.storage.StoredBlob
import java.io.InputStream

/**
 * Blobs in Google Cloud Storage.
 *
 * Everything that makes the archive trustworthy is inherited from [BlobStore] and
 * unchanged from the filesystem version — the key is the content's hash, so writing the
 * same bytes twice costs nothing and a stored object can never disagree with the hash a
 * row records for it. What is here is moving bytes.
 *
 * **Object versioning is deliberately not enabled**, and that is not an oversight. A
 * version records that a key's content changed; under content addressing it cannot — a
 * key names exactly one sequence of bytes for ever. Turning it on would pay for storage
 * that can only hold identical copies.
 *
 * Bucket names carry a prefix because a name is global to all of Google Cloud: `raw`
 * belongs to somebody else and has since before this project existed.
 */
class GcsBlobStore(
    private val storage: Storage,
    private val bucketPrefix: String,
    private val location: String,
) : BlobStore {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun store(bucket: BlobBucket, payload: ByteArray, mediaType: String): StoredBlob {
        val contentHash = ContentHash.of(payload)

        // Asked before writing, because the answer is the whole point of content
        // addressing: the same PDF reached from two sources occupies space once.
        if (exists(bucket, contentHash)) {
            return StoredBlob(contentHash, bucket, payload.size.toLong(), mediaType, deduplicated = true)
        }

        storage.create(
            BlobInfo.newBuilder(idOf(bucket, contentHash)).setContentType(mediaType).build(),
            payload,
            // Refuses the write if something is already at this key. Two ingestion runs
            // can offer the same bytes at the same instant, and the loser being told so
            // is better than a second upload of what is already there.
            Storage.BlobTargetOption.doesNotExist(),
        )

        return StoredBlob(contentHash, bucket, payload.size.toLong(), mediaType, deduplicated = false)
    }

    override fun read(bucket: BlobBucket, contentHash: ContentHash): InputStream? =
        // Absent, not broken. A caller asking for a hash nothing was stored under is
        // asking a reasonable question and gets a plain answer.
        storage.get(idOf(bucket, contentHash))?.reader()?.let { java.nio.channels.Channels.newInputStream(it) }

    override fun exists(bucket: BlobBucket, contentHash: ContentHash): Boolean =
        storage.get(idOf(bucket, contentHash)) != null

    override fun delete(bucket: BlobBucket, contentHash: ContentHash): Boolean =
        storage.delete(idOf(bucket, contentHash))

    /**
     * Creates the three buckets if they are not there.
     *
     * At startup rather than on first write, so a wrong project or a service account
     * without permission stops the application instead of failing the first ingestion
     * run of the night. A deployment whose buckets are provisioned elsewhere — by
     * Terraform, with the workload holding object permissions only — finds them
     * present and creates nothing.
     */
    fun ensureBuckets() {
        BlobBucket.entries.forEach { bucket ->
            val name = nameOf(bucket)
            if (storage.get(name) != null) return@forEach

            try {
                storage.create(BucketInfo.newBuilder(name).setLocation(location).build())
                log.info("Created object storage bucket {}", name)
            } catch (conflict: StorageException) {
                // Several instances start at once and exactly one of them wins; the
                // others are looking at a bucket that now exists, which is what they
                // wanted. Anything else is a real failure and stops the start.
                if (conflict.code != ALREADY_OWNED) throw conflict
            }
        }
    }

    private fun idOf(bucket: BlobBucket, contentHash: ContentHash): BlobId =
        BlobId.of(nameOf(bucket), keyOf(contentHash))

    private fun nameOf(bucket: BlobBucket) = "$bucketPrefix-${bucket.bucketName}"

    private companion object {
        /** `409 Conflict`: the bucket exists and this project already owns it. */
        const val ALREADY_OWNED = 409
    }
}
