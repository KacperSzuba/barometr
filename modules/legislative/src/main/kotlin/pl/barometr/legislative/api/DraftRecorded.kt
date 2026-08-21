package pl.barometr.legislative.api

import java.time.Instant

/** Published when a draft has been recorded, or its path restated. */
data class DraftRecorded(val draftId: DraftId, val occurredAt: Instant)
