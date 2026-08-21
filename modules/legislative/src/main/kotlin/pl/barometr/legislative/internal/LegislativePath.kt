package pl.barometr.legislative.internal

/**
 * Which stage may follow which — the process model, stated once.
 *
 * Loosely on purpose. A bill going back to committee between its second and third
 * readings is not an anomaly, it is most Thursdays, and a model that called it one
 * would mark half the archive exceptional and make the flag worth nothing. So the
 * edges below include the returns the process really makes, and what stays
 * exceptional is a jump the process does not make at all — which is the only kind
 * worth a reader's attention.
 *
 * Nothing is ever rejected for failing this. An unexpected transition is recorded
 * with `is_exception`, because reality is the thing being described and a schema that
 * argues with it loses data.
 */
object LegislativePath {

    private val ALLOWED: Map<LegislativeStage, Set<LegislativeStage>> = mapOf(
        LegislativeStage.SUBMITTED_TO_SEJM to setOf(LegislativeStage.REFERRED_TO_FIRST_READING),
        LegislativeStage.REFERRED_TO_FIRST_READING to setOf(LegislativeStage.FIRST_READING),
        LegislativeStage.FIRST_READING to setOf(
            LegislativeStage.COMMITTEE_WORK,
            LegislativeStage.SECOND_READING,
        ),
        LegislativeStage.COMMITTEE_WORK to setOf(
            LegislativeStage.SECOND_READING,
            LegislativeStage.THIRD_READING,
        ),
        // The routine return: amendments at second reading send it back to committee
        // for a report, and it comes out to a third reading the same afternoon.
        LegislativeStage.SECOND_READING to setOf(
            LegislativeStage.COMMITTEE_WORK,
            LegislativeStage.THIRD_READING,
        ),
        LegislativeStage.THIRD_READING to setOf(LegislativeStage.SENATE_POSITION),
        // The Senate's amendments go back to the Sejm to be voted on, so a further
        // committee sitting after the Senate is ordinary too.
        LegislativeStage.SENATE_POSITION to setOf(
            LegislativeStage.SENT_TO_PRESIDENT,
            LegislativeStage.COMMITTEE_WORK,
            LegislativeStage.THIRD_READING,
        ),
        LegislativeStage.SENT_TO_PRESIDENT to setOf(
            LegislativeStage.PRESIDENT_SIGNED,
            LegislativeStage.PRESIDENT_TO_TRIBUNAL,
        ),
        LegislativeStage.PRESIDENT_TO_TRIBUNAL to setOf(LegislativeStage.PRESIDENT_SIGNED),
    )

    /**
     * True when the model expects this step.
     *
     * A step into or out of [LegislativeStage.UNKNOWN] is never called exceptional:
     * the model has nothing to say about a stage it could not read, and flagging it
     * would report our own gap as the source's irregularity.
     */
    fun allows(from: LegislativeStage, to: LegislativeStage): Boolean = when {
        from == LegislativeStage.UNKNOWN || to == LegislativeStage.UNKNOWN -> true
        from == to -> true
        else -> ALLOWED[from].orEmpty().contains(to)
    }
}
