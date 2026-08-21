package pl.barometr.alerts.internal

/**
 * What a run decided about one person and one item, and the rule that decided it.
 *
 * Both halves are recorded, including for the decisions that sent nothing: "why did I
 * not get an alert about this" is otherwise unanswerable, and it is the question
 * support gets asked.
 */
data class AlertOutcome(val decision: Decision, val reason: String) {

    enum class Decision(val wireName: String) { RAISED("raised"), WITHHELD("withheld") }

    companion object {
        /** The profile caught it, the rule allows it, nobody has been told yet. */
        val RAISED = AlertOutcome(Decision.RAISED, "matched")

        /** No rule for this profile: somebody described an interest and asked for nothing. */
        val NO_RULE = withheld("no_rule")

        val RULE_DISABLED = withheld("rule_disabled")

        /** The draft is at a stage this rule does not watch. */
        val STAGE_NOT_WATCHED = withheld("stage_not_watched")

        /** This exact piece of news has already been raised for this person. */
        val ALREADY_TOLD = withheld("already_told")

        /** Something else about the same matter reached them inside the window. */
        val CASE_RECENTLY_RAISED = withheld("case_recently_raised")

        private fun withheld(reason: String) = AlertOutcome(Decision.WITHHELD, reason)
    }
}
