package pl.barometr.legislative.internal

/**
 * A Sejm print an act names as its origin — the bridge between what was voted and
 * what was published.
 *
 * The single most valuable field ISAP gives us: without it, matching a draft to the
 * act it became is title similarity and hope.
 */
data class SejmPrintReference(val term: Int, val number: String) {
    val documentAddress: String get() = SejmPrintAddress.of(term, number)
}
