package pl.barometr.legislative.internal

/** One number a draft is quoted by, in the register that issued it. */
data class DraftIdentifierValue(val scheme: DraftIdentifierScheme, val value: String)
