package pl.barometr.storage

/** Retention and access differ per kind, so each gets its own bucket. */
enum class BlobBucket(val bucketName: String) {
    /** Untouched source payloads. Never deleted — this is the archive. */
    RAW("raw"),

    /** Extracted text, thumbnails, anything recomputable from RAW. */
    DERIVED("derived"),

    /** User-facing exports, expired on a schedule. */
    EXPORTS("exports"),
}
