package pl.barometr.sources.api

enum class RunOutcome(val wireName: String) {
    SUCCEEDED("succeeded"),

    /** Some documents made it through, some did not. Worth knowing separately. */
    PARTIAL("partial"),
    FAILED("failed"),
}
