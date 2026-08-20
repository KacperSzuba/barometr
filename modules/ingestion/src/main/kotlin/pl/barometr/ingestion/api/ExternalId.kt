package pl.barometr.ingestion.api

/** The source's own identifier for a document: print number, RCL id, ELI, URL. */
@JvmInline
value class ExternalId(val value: String) {
    init {
        require(value.isNotBlank()) { "External id must not be blank" }
    }

    override fun toString(): String = value
}
