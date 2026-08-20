package pl.barometr.ingestion.api

/**
 * A count the source itself publishes, to check our archive against.
 *
 * The distinction in [isAuthoritative] is the whole point of this type. A figure the
 * API states independently — "this term contains 3205 prints" — genuinely proves
 * completeness. A figure derived from the same list we ingested only proves we
 * finished reading that list, which catches a truncated backfill but cannot detect
 * that the list itself was short. Reporting the two as if they were the same
 * evidence would make the completeness report worse than useless: falsely
 * reassuring.
 */
data class DeclaredVolume(
    val partition: String,
    /** What kind of thing is being counted: `print`, `proceeding`. */
    val kind: String,
    /** Archive rows whose external id starts with this belong to the count. */
    val externalIdPrefix: String,
    val declaredCount: Int,
    val isAuthoritative: Boolean,
)
