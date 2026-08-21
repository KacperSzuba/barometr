package pl.barometr.legislative.internal

/** Everything one draft's card shows: what it is, where it is, and how it got there. */
data class DraftCard(
    val draft: DraftSummary,
    val status: DraftStatus?,
    val history: List<RecordedStage>,
)
