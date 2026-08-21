package pl.barometr.legislative.internal

/**
 * How a draft's passage ended, matching the `CHECK` on `draft.outcome`.
 *
 * Separate from [LegislativeStage] because it is a verdict on the whole passage
 * rather than a place the draft was on a given day: the register states it without a
 * date, and putting it in the timeline would sit the Sejm's vote before the Senate
 * and the President.
 *
 * Absent while a draft is still moving, which is the answer for most of them.
 */
enum class DraftOutcome(val wireName: String) {
    ENACTED("uchwalony"),
    REJECTED("odrzucony"),
}
