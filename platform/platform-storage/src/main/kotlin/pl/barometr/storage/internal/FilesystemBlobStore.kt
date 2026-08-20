package pl.barometr.storage.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import pl.barometr.shared.ContentHash
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import pl.barometr.storage.StoredBlob
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@ConfigurationProperties(prefix = "app.storage")
data class StorageProperties(
    /** Root directory for the filesystem implementation. */
    val root: Path,
)

/**
 * Filesystem-backed blob store.
 *
 * Everything interesting about the design — content addressing, deduplication,
 * key sharding — lives in [BlobStore] and applies unchanged to S3. What is left
 * here is moving bytes, which makes the S3 adapter a small, isolated addition
 * rather than a rewrite, and lets development and tests run with no object
 * storage at all.
 */
@Component
class FilesystemBlobStore(private val properties: StorageProperties) : BlobStore {

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

    private fun pathFor(bucket: BlobBucket, contentHash: ContentHash): Path =
        properties.root.resolve(bucket.bucketName).resolve(keyOf(contentHash))
}
