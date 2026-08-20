package pl.barometr.http

/**
 * Whether this source's robots.txt is honoured.
 *
 * A sealed type rather than a boolean, because the two states are not symmetric.
 * Respecting robots.txt needs no justification; overriding it does — so the
 * override cannot be expressed without writing one down, and the written reason
 * travels with the configuration into logs and into the source registry.
 *
 * Deliberately impossible to set quietly. An exemption that nobody can see is the
 * shape this takes when someone is working around an access restriction rather
 * than standing on a right to the data; an exemption that announces itself on
 * every run is the shape it takes when the right is real.
 */
sealed interface RobotsPolicy {

    data object Respect : RobotsPolicy

    /**
     * Reads the source despite its robots.txt, on a stated basis.
     *
     * [legalBasis] is what makes this defensible: the ground the operator of this
     * system stands on — a statutory right of access, or permission granted by the
     * source. It is recorded, logged and answerable, not a flag.
     */
    data class Exempt(val legalBasis: String) : RobotsPolicy {
        init {
            require(legalBasis.isNotBlank()) {
                "A robots.txt exemption requires a written legal basis"
            }
            require(legalBasis.length >= MINIMUM_BASIS_LENGTH) {
                "State the actual basis, not a placeholder: '$legalBasis'"
            }
        }

        private companion object {
            const val MINIMUM_BASIS_LENGTH = 20
        }
    }
}
