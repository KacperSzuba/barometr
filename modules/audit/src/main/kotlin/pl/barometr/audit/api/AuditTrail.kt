package pl.barometr.audit.api

/**
 * Records that something was attempted.
 *
 * The only thing this context publishes, and it is a verb with no return: a caller
 * cannot read the trail back, cannot correct an entry, and cannot be told whether one
 * was written. Recording is not the caller's business to succeed or fail at — it is the
 * system's, and the log is not theirs to negotiate with.
 */
interface AuditTrail {

    fun record(attempt: AuditableAttempt)
}
