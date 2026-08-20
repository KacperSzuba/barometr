package pl.barometr.ingestion.api

/**
 * One document as the source returned it.
 *
 * A plain class rather than a `data class` on purpose: generated equality over a
 * [ByteArray] compares references, which would be quietly wrong wherever two
 * payloads are checked for sameness. Identity of a payload is its content hash,
 * and computing that is the sink's job.
 */
class RawPayload(
    val externalId: ExternalId,
    val payload: ByteArray,
    val kind: PayloadKind,
    /** Passed straight through so the next run can make a conditional request. */
    val etag: String? = null,
    val lastModified: String? = null,
)
