package pl.barometr.legislative.internal

import pl.barometr.corpus.api.DocumentId
import pl.barometr.legislative.api.ActId
import java.time.Instant
import java.util.UUID

/** A match a person still has to decide, and everything they need to decide it. */
data class PendingActMatch(
    val id: UUID,
    val documentId: DocumentId,
    val actId: ActId?,
    val scheme: IdentifierScheme,
    val value: String,
    val confidence: Double,
    val createdAt: Instant,
)
