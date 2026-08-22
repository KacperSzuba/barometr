package pl.barometr.storage

/**
 * What the blobs are kept on.
 *
 * Two, and the difference matters operationally rather than in the code: everything
 * interesting — content addressing, deduplication, the sharded key — lives in
 * [BlobStore] and is identical either way.
 */
enum class StorageKind {
    /** A directory. One instance, one disk, and a volume somebody has to back up. */
    FILESYSTEM,

    /** Google Cloud Storage: replicated, versioned by the provider, and not ours to lose. */
    GCS,
}
