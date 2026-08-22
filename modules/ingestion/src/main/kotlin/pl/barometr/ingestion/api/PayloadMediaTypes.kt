package pl.barometr.ingestion.api

/**
 * The media type a payload kind is stored under, and the reading back of it.
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

    /**
     * What a `Content-Type` header names, or null for a type this system has no kind
     * for.
     *
     * Derived from [of] rather than written out again. An inverse table maintained by
     * hand is a second declaration of the same fact, and the failure it produces —
     * a payload archived under one media type and read back as another — is invisible
     * until something tries to parse it.
     */
    fun kindOf(mediaType: String?): PayloadKind? {
        // "application/pdf; charset=binary" is one type with a parameter, not two.
        val bare = mediaType?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return BY_MEDIA_TYPE[bare]
    }

    private val BY_MEDIA_TYPE: Map<String, PayloadKind> = PayloadKind.entries.associateBy { of(it) }
}
