package pl.barometr.legislative.internal

/**
 * The two registers the same draft is written in, and the number each one keys it by.
 *
 * A draft exists in RPL for months before the Sejm has heard of it, and the two
 * registers never print each other's numbers, so a draft row belongs to exactly one of
 * these until something joins them. Which one it belongs to is not a column — it is
 * which identifier the row was claimed under, and reading it from there keeps the two
 * facts one.
 */
enum class DraftRegister(val claimedBy: DraftIdentifierScheme) {

    /** RPL: the government's own process, from the day a ministry files the draft. */
    GOVERNMENT(DraftIdentifierScheme.RCL_PROJECT),

    /** The Sejm's register, which begins at the print number. */
    SEJM(DraftIdentifierScheme.SEJM_PRINT);

    val counterpart: DraftRegister get() = if (this == GOVERNMENT) SEJM else GOVERNMENT
}
