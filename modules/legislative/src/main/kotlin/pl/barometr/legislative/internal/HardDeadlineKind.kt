package pl.barometr.legislative.internal

/**
 * What kind of hard date a draft is up against.
 *
 * "Hard" is the whole point of the type existing. A date computed from a statute is a
 * different claim from a date estimated out of historical medians, and this product
 * cannot afford a reader treating one as the other — so they are separate columns,
 * separate fields and separate words all the way out to the API.
 */
enum class HardDeadlineKind(val wireName: String) {
    /** The day the act starts applying, as the journal itself states it. */
    ENTRY_INTO_FORCE("wejscie_w_zycie"),
}
