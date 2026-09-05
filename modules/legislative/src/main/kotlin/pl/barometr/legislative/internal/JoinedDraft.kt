package pl.barometr.legislative.internal

/**
 * The same draft as the other register holds it: the half of the story the row being
 * read does not carry.
 *
 * How the join was made travels with it, and that is not decoration. A join a person
 * made and one two titles suggested are different claims, and a reader deciding
 * whether to act on a consultation deserves to know which they are looking at.
 */
data class JoinedDraft(
    val draft: DraftSummary,
    /** Which register this half comes from — the government's process, or the Sejm's. */
    val register: DraftRegister,
    val joinedBy: MatchMethod,
    val confidence: Double?,
    val history: List<RecordedStage>,
)
