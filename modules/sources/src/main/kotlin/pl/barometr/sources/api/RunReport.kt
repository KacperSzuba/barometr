package pl.barometr.sources.api

data class RunReport(
    val documentsSeen: Int,
    val documentsStored: Int,
    val errors: Int,
    /** Fields the response carried unexpectedly, or omitted. Recorded, not thrown. */
    val schemaWarnings: List<String>,
    val failureReason: String? = null,
)
