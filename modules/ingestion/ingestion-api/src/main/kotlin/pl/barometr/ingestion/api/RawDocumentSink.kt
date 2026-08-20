package pl.barometr.ingestion.api

/** The source's own identifier for a document: print number, RCL id, ELI, URL. */
@JvmInline
value class ExternalId(val value: String) {
    init {
        require(value.isNotBlank()) { "External id must not be blank" }
    }

    override fun toString(): String = value
}

enum class PayloadKind(val wireName: String) {
    JSON("json"),
    XML("xml"),
    HTML("html"),
    PDF("pdf"),
    DOC("doc"),
    DOCX("docx"),
    CSV("csv"),
    BINARY("binary"),
}

/**
 * One document as the source returned it.
 *
 * A plain class rather than a `data class` on purpose: generated equality over a
 * [ByteArray] compares references, which would be quietly wrong wherever two
 * payloads are checked for sameness. Identity of a payload is its content hash,
 * and computing that is the sink's job.
 */
class RawPayload(
    val externalId: ExternalId,
    val payload: ByteArray,
    val kind: PayloadKind,
    /** Passed straight through so the next run can make a conditional request. */
    val etag: String? = null,
    val lastModified: String? = null,
)

enum class SinkOutcome {
    /** New content; downstream processing has been triggered. */
    STORED,

    /** This exact content was already recorded. Nothing happened, and that is correct. */
    ALREADY_KNOWN,
}

/**
 * The only way a connector writes anything.
 *
 * Everything a connector could get wrong lives behind this interface: hashing,
 * storing the payload under its content address, the `ON CONFLICT DO NOTHING`
 * insert, and publishing the event that starts the processing pipeline. A
 * connector cannot deduplicate incorrectly because it has no access to the
 * mechanism, and cannot write to the wrong source because the sink handed to it
 * is already bound to one.
 *
 * Which is what makes a connector a pure function from a source to a stream of
 * payloads — and testable against a recorded response with no database in sight.
 */
interface RawDocumentSink {

    fun accept(payload: RawPayload): SinkOutcome

    /**
     * Recorded when a response carries a field the connector does not know, or
     * omits one it expects. A source changing shape underneath us shows up here
     * before it shows up as missing data.
     */
    fun warn(warning: SchemaWarning)
}

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
