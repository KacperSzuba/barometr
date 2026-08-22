package pl.barometr.legislative.internal

/** One number an act is quoted by, and how this system came to believe it. */
data class ActIdentifierValue(
    val scheme: IdentifierScheme,
    val value: String,
    /** `exact`, `fuzzy` or `manual` — the register's word, a similarity, or a person. */
    val resolvedBy: String,
)
