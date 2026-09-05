package pl.barometr.alerts.internal

import pl.barometr.legislative.api.ConsultationId
import java.time.Instant

/** One consultation this person has written in about, and what they said they filed. */
data class RecordedFiling(
    val consultation: ConsultationId,
    val filedAt: Instant,
    val note: String?,
)
