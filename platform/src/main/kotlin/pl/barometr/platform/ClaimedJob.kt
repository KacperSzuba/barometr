package pl.barometr.platform

import java.util.UUID

data class ClaimedJob(
    val id: UUID,
    val type: JobType,
    val payload: String,
    /** 1 on the first run. Lets a handler behave differently on a retry. */
    val attempt: Int,
    val maxAttempts: Int,
) {
    val isFinalAttempt: Boolean get() = attempt >= maxAttempts
}
