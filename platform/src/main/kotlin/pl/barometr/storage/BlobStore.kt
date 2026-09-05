package pl.barometr.storage

import pl.barometr.shared.ContentHash
import java.io.InputStream

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
     * Removes an object. `false` when there was nothing at that address.
     *
     * Deliberately narrow in what it is for. The raw bucket is the archive and nothing
     * deletes from it; what this exists for is the two buckets that are allowed to
     * forget — an export somebody downloaded and no longer has a right to keep on our
     * disk, and derived data being recomputed.
     *
     * Content addressing has one consequence worth stating: two callers storing
     * identical bytes share an object, so deleting one deletes the other's too. In the
     * buckets this is used for, identical bytes mean the same document by construction —
     * an export carries the account it is about.
     */
    fun delete(bucket: BlobBucket, contentHash: ContentHash): Boolean

    /**
     * Storage key for a hash. Sharded on the first two hex pairs, because a flat
     * namespace of millions of objects is painful in every filesystem and in some
     * S3 console tooling.
     */
    fun keyOf(contentHash: ContentHash): String = with(contentHash.hex) {
        "${substring(0, 2)}/${substring(2, 4)}/$this"
    }
}
