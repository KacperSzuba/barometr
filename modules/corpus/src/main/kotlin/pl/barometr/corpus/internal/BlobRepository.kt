package pl.barometr.corpus.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.corpus.internal.jooq.tables.references.BLOB
import pl.barometr.shared.ContentHash
import pl.barometr.storage.BlobBucket
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The index of what the object store holds. SQL only.
 *
 * The row is not a copy of the blob, it is the database's knowledge that the blob
 * exists — which is what lets `document_version.content_hash` be a foreign key, and
 * therefore what stops a version pointing at bytes nobody stored.
 */
@Repository
class BlobRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Idempotent by construction: the content hash is the primary key, so the same
     * bytes reaching us from two sources record one row and the second write is a
     * no-op rather than a conflict to handle.
     */
    fun recordStoredBlob(contentHash: ContentHash, byteSize: Long, mediaType: String, bucket: BlobBucket) {
        dsl.insertInto(BLOB)
            .set(BLOB.CONTENT_HASH, contentHash.bytes)
            .set(BLOB.BYTE_SIZE, byteSize)
            .set(BLOB.MEDIA_TYPE, mediaType)
            .set(BLOB.BUCKET, bucket.bucketName)
            .set(BLOB.STORED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute()
    }
}
