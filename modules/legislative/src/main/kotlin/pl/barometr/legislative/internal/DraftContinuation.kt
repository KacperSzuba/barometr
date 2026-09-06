package pl.barometr.legislative.internal

import pl.barometr.legislative.api.DraftId
import java.time.Instant

/**
 * The government's draft and the print it became, recorded as one case.
 *
 * Kept in the order the process runs in rather than as "this one and the other one":
 * which of the pair is the government's is a fact about the draft, not about which of
 * them a reader happened to open.
 */
data class DraftContinuation(
    val governmentDraftId: DraftId,
    val sejmDraftId: DraftId,
    /** The register stated a shared number, titles were compared, or a person decided. */
    val joinedBy: MatchMethod,
    /** The similarity that carried a fuzzy join; null when a number or a person did. */
    val confidence: Double?,
    val joinedAt: Instant,
) {

    fun counterpartOf(draftId: DraftId): DraftId? = when (draftId) {
        governmentDraftId -> sejmDraftId
        sejmDraftId -> governmentDraftId
        else -> null
    }
}
