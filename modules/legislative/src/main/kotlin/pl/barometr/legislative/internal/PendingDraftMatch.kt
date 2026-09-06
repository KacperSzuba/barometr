package pl.barometr.legislative.internal

import pl.barometr.legislative.api.DraftId
import java.time.Instant
import java.util.UUID

/**
 * A join a person still has to decide, and everything they need to decide it.
 *
 * The titles travel with the ids because they are the decision: a reviewer looking at
 * two identifiers has been handed the question without the evidence, and would have to
 * open both drafts to answer it.
 */
data class PendingDraftMatch(
    val id: UUID,
    val governmentDraftId: DraftId,
    val governmentTitle: String,
    val sejmDraftId: DraftId,
    val sejmTitle: String,
    val confidence: Double,
    val createdAt: Instant,
)
