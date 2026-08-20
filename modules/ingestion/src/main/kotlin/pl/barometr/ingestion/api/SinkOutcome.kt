package pl.barometr.ingestion.api

enum class SinkOutcome {
    /** New content; downstream processing has been triggered. */
    STORED,

    /** This exact content was already recorded. Nothing happened, and that is correct. */
    ALREADY_KNOWN,
}
