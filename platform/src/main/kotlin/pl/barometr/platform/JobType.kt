package pl.barometr.platform

/** Discriminator a worker registers against, e.g. `ingest.sejm.incremental`. */
@JvmInline
value class JobType(val value: String) {
    init {
        require(value.isNotBlank()) { "Job type must not be blank" }
    }

    override fun toString(): String = value
}
