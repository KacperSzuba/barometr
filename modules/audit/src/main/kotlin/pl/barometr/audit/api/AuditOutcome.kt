package pl.barometr.audit.api

/**
 * How an attempt ended.
 *
 * Four rather than a boolean, because the three ways of not succeeding are three
 * different things to whoever reads this log. A denial is a guardrail doing its job; a
 * rejection is somebody sending nonsense; a failure is ours. Collapsing them would put
 * the one entry this table exists for — the denial — in a bucket with typos.
 */
enum class AuditOutcome(val wireName: String) {
    SUCCEEDED("succeeded"),

    /** Refused for lack of authority: no token, wrong role, somebody else's data. */
    DENIED("denied"),

    /** Refused for what it said: a malformed body, an address that is not one. */
    REJECTED("rejected"),

    /** It broke. Not the caller's doing, and worth seeing beside the rest. */
    FAILED("failed"),
    ;

    companion object {
        fun of(wireName: String): AuditOutcome? = entries.firstOrNull { it.wireName == wireName }
    }
}
