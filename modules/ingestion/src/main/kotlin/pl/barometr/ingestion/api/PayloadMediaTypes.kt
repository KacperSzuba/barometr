package pl.barometr.ingestion.api

/**
 * The media type a payload kind is stored under.
 *
 * Its own file because it is a lookup table, and a lookup table inlined into a
 * method about ingestion policy is the kind of detail that makes the surrounding
 * logic harder to read than it needs to be.
 *
 * Published rather than internal, because it maps a published enum: the context that
 * reads a payload back out of the archive has to record the same media type the
 * archiver stored it under, and a second copy of this table is a second answer.
 */
object PayloadMediaTypes {

    fun of(kind: PayloadKind): String = when (kind) {
        PayloadKind.JSON -> "application/json"
        PayloadKind.XML -> "application/xml"
        PayloadKind.HTML -> "text/html"
        PayloadKind.PDF -> "application/pdf"
        PayloadKind.DOC -> "application/msword"
        PayloadKind.DOCX ->
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        PayloadKind.CSV -> "text/csv"
        PayloadKind.BINARY -> "application/octet-stream"
    }
}
