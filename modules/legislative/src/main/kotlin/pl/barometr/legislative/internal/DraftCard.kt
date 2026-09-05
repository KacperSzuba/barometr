package pl.barometr.legislative.internal

/**
 * Everything one draft's card shows: what it is, where it is, how it got there, and —
 * when the two registers have been joined — the half of its life the other one holds.
 */
data class DraftCard(
    val draft: DraftSummary,
    val status: DraftStatus?,
    val history: List<RecordedStage>,
    /** Null while nothing has been joined to this draft, which is most of them. */
    val otherRegister: JoinedDraft?,
)
