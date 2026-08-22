package pl.barometr.platform

import java.util.UUID

data class ClaimedJob(
    val id: UUID,
    val type: JobType,
    val payload: String,
    /** 1 on the first run. Lets a handler behave differently on a retry. */
    val attempt: Int,
    val maxAttempts: Int,
    /**
     * The trace of whoever queued this, opaque and for the worker to restore.
     *
     * Null when nothing was tracing at the time, which is every module test and an
     * application with no exporter configured.
     */
    val traceContext: String? = null,
) {
    val isFinalAttempt: Boolean get() = attempt >= maxAttempts
}
