package pl.barometr.storage

import pl.barometr.shared.ContentHash

data class StoredBlob(
    val contentHash: ContentHash,
    val bucket: BlobBucket,
    val byteSize: Long,
    val mediaType: String,
    /** True when identical content was already present and nothing was written. */
    val deduplicated: Boolean,
)
