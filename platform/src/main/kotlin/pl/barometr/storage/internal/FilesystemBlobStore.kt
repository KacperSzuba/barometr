package pl.barometr.storage.internal

import pl.barometr.shared.ContentHash
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import pl.barometr.storage.StoredBlob
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Filesystem-backed blob store.
 *
 * Everything interesting about the design — content addressing, deduplication,
 * key sharding — lives in [BlobStore] and applies unchanged to S3. What is left
 * here is moving bytes, which makes the S3 adapter a small, isolated addition
 * rather than a rewrite, and lets development and tests run with no object
 * storage at all.
 */
class FilesystemBlobStore(root: Path) : BlobStore {

    /**
     * Required, and checked here rather than left to fail on the first write.
     *
     * A store with nowhere to write is not a store, and the archive is the one thing
     * in this system that cannot be recomputed — finding out at the first document
     * rather than at startup is finding out too late.
     */
    private val root: Path = requireNotNull(root) { "app.storage.root must be set" }

    override fun store(bucket: BlobBucket, payload: ByteArray, mediaType: String): StoredBlob {
        val contentHash = ContentHash.of(payload)
        val target = pathFor(bucket, contentHash)

        if (Files.exists(target)) {
            // Identical content already stored. Not an error and not a conflict:
            // the hash guarantees the existing bytes are the bytes being offered.
            return StoredBlob(contentHash, bucket, Files.size(target), mediaType, deduplicated = true)
        }

        Files.createDirectories(target.parent)
        // Write beside the target and move into place, so a crash mid-write can
        // never leave a truncated object at an address that claims to be a
        // complete one.
        val staging = Files.createTempFile(target.parent, ".staging-", ".tmp")
        try {
            Files.write(staging, payload)
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Exception) {
            Files.deleteIfExists(staging)
            throw failure
        }

        return StoredBlob(contentHash, bucket, payload.size.toLong(), mediaType, deduplicated = false)
    }

    override fun read(bucket: BlobBucket, contentHash: ContentHash): InputStream? {
        val path = pathFor(bucket, contentHash)
        return if (Files.exists(path)) Files.newInputStream(path) else null
    }

    override fun exists(bucket: BlobBucket, contentHash: ContentHash): Boolean =
        Files.exists(pathFor(bucket, contentHash))

    override fun delete(bucket: BlobBucket, contentHash: ContentHash): Boolean =
        Files.deleteIfExists(pathFor(bucket, contentHash))

    private fun pathFor(bucket: BlobBucket, contentHash: ContentHash): Path =
        root.resolve(bucket.bucketName).resolve(keyOf(contentHash))
}
