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

    /**
     * Taken back by whoever filed it, which is not the same as being voted down and
     * was being reported as such: the register says `passed: false` for both, and only
     * the closing entry's own word tells them apart.
     */
    WITHDRAWN("wycofany"),
    ;

    companion object {
        /** The register's closing word, which is more precise than `passed`. */
        fun of(closingLabel: String): DraftOutcome? = when {
            closingLabel.startsWith("Uchwalono") -> ENACTED
            closingLabel.startsWith("Odrzucono") -> REJECTED
            closingLabel.startsWith("Wycofano") -> WITHDRAWN
            else -> null
        }
    }
}
