package pl.barometr.storage

import pl.barometr.shared.ContentHash
import java.io.InputStream

/** Retention and access differ per kind, so each gets its own bucket. */
enum class BlobBucket(val bucketName: String) {
    /** Untouched source payloads. Never deleted — this is the archive. */
    RAW("raw"),

    /** Extracted text, thumbnails, anything recomputable from RAW. */
    DERIVED("derived"),

    /** User-facing exports, expired on a schedule. */
    EXPORTS("exports"),
}

data class StoredBlob(
    val contentHash: ContentHash,
    val bucket: BlobBucket,
    val byteSize: Long,
    val mediaType: String,
    /** True when identical content was already present and nothing was written. */
    val deduplicated: Boolean,
)

/**
 * Content-addressed object storage: the key *is* the SHA-256 of the payload.
 *
 * That single decision buys three properties at once. Storing the same bytes
 * twice is free, because the second write resolves to the same key. Writes are
 * idempotent, so a connector may safely retry a partial run. And a stored object
 * can never silently disagree with the hash a database row records for it.
 *
 * Blobs never live in Postgres — the database holds the hash and a reference.
 */
interface BlobStore {

    /** Idempotent: identical content stores once, however many callers submit it. */
    fun store(bucket: BlobBucket, payload: ByteArray, mediaType: String): StoredBlob

    fun read(bucket: BlobBucket, contentHash: ContentHash): InputStream?

    fun exists(bucket: BlobBucket, contentHash: ContentHash): Boolean

    /**
     * Storage key for a hash. Sharded on the first two hex pairs, because a flat
     * namespace of millions of objects is painful in every filesystem and in some
     * S3 console tooling.
     */
    fun keyOf(contentHash: ContentHash): String = with(contentHash.hex) {
        "${substring(0, 2)}/${substring(2, 4)}/$this"
    }
}
