package pl.barometr.identity.internal.user

/**
 * What a deployment with no address database gets: no guesses.
 *
 * A default rather than an absence, so that everything above can ask the question
 * unconditionally — and so that turning the feature on is a path in configuration rather
 * than a branch in the code.
 */
object UnknownLocations : ApproximateLocations {
    override fun locate(clientIp: String?): String? = null
}
