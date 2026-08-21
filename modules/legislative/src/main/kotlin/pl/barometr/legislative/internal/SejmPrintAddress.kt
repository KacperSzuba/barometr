package pl.barometr.legislative.internal

/**
 * How a Sejm print is addressed in the archive: `term10/print/424`.
 *
 * Stated here because the identifier recorded against an act must be the *same
 * string* the corpus holds as that print's external id. Then pinning a print to its
 * act is one equality lookup, rather than a parse of a number out of one format and
 * back into another.
 *
 * That makes this the third statement of one format — `SejmExternalIds` builds it and
 * the corpus reader recognises it — and the reason is the same all three times: the
 * archive is the contract between contexts, so each of them must be able to say the
 * address without importing another's internals. The addresses are pinned by a test
 * in each place rather than by a shared type nobody could change independently.
 */
object SejmPrintAddress {

    fun of(term: Int, number: String): String = "term$term/print/$number"
}
