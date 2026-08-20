package pl.barometr.ingestion.api

data class SchemaWarning(
    /** Where in the response, e.g. `votings[].kind`. */
    val path: String,
    val kind: Kind,
    val detail: String? = null,
) {
    enum class Kind {
        UNKNOWN_FIELD,
        MISSING_FIELD,
        UNEXPECTED_TYPE,

        /**
         * The source refused a resource — robots.txt or a rights reservation.
         * A gap in the archive with a legal cause, worth telling apart from a
         * shape change.
         */
        ACCESS_DENIED,
    }
}
