package pl.barometr.connectors.rcl

import java.time.LocalDateTime

/**
 * One entry in a draft's or a catalog's event log.
 *
 * [occurredAt] is accurate to the minute, which is the reason these pages are
 * fetched at all. A project card says a stage was last touched on some date; the
 * register says it changed at 15:24 that day, and a bitemporal record wants the
 * latter for its `valid_from`.
 */
data class RclChange(
    val occurredAt: LocalDateTime?,
    /**
     * Who made the change. Sometimes an institution — "Minister Sprawiedliwości",
     * "Administrator" — and sometimes a named civil servant.
     *
     * The named case is personal data published by RPL itself. It travels through
     * the connector because dropping it here would silently break provenance, but
     * what the system retains and displays is a separate decision that belongs with
     * the source's recorded legal basis, not with a parser.
     */
    val author: String,
    val description: String,
    val kind: RclChangeKind,
    /** For [RclChangeKind.ATTRIBUTE_CHANGED]: which attribute, and its new value. */
    val attribute: String? = null,
    val newValue: String? = null,
    /** For [RclChangeKind.CATALOG_ADDED]: the child catalog's name. */
    val catalogName: String? = null,
    /** For [RclChangeKind.DOCUMENT_ADDED]: the file name, as filed. */
    val documentName: String? = null,
    /** Set when the entry links the catalog it concerns. */
    val catalogId: String? = null,
)
