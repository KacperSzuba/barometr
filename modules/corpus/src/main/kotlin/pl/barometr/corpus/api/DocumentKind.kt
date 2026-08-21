package pl.barometr.corpus.api

/**
 * What sort of thing a document is: `print`, `voting`, `act`, `rcl-project`.
 *
 * Deliberately open rather than an enum. The set grows with every source, and a
 * closed type would mean a new source could not be archived until this context was
 * changed to admit its vocabulary — the coupling the module boundary exists to
 * prevent.
 */
@JvmInline
value class DocumentKind(val value: String) {
    init {
        require(value.matches(PATTERN)) { "Document kind must be lower-kebab-case: '$value'" }
    }

    override fun toString(): String = value

    companion object {
        private val PATTERN = Regex("[a-z][a-z0-9-]*")

        /**
         * A document whose source we can read but whose shape we cannot place.
         *
         * Recorded rather than dropped: the version chain and the provenance are
         * still correct, and a document classified wrongly is visible where a
         * document silently missing from the corpus is not.
         */
        val UNKNOWN = DocumentKind("unknown")
    }
}
