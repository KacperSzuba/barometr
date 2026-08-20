package pl.barometr.ingestion.internal

import pl.barometr.ingestion.api.PayloadKind

/**
 * The media type a payload kind is stored under.
 *
 * Its own file because it is a lookup table, and a lookup table inlined into a
 * method about ingestion policy is the kind of detail that makes the surrounding
 * logic harder to read than it needs to be.
 */
internal object PayloadMediaTypes {

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
