package pl.barometr.connectors.isap

import pl.barometr.ingestion.api.SchemaWarning

/**
 * One page of a listing, and what the listing as a whole says it holds.
 */
data class IsapPage(
    val acts: List<IsapAct>,
    /**
     * Stated by the API for the whole filter, independently of this page. It drives
     * both paging and the completeness audit.
     */
    val totalCount: Int,
    /**
     * How many items the page actually carried, which is not always [acts] size:
     * an item the client could not address is dropped. Paging advances by this
     * number, because advancing by the readable ones would re-request the same
     * offset forever the moment a single item went unreadable.
     */
    val itemsServed: Int,
    /** One per dropped item, for the connector to record on the run. */
    val warnings: List<SchemaWarning> = emptyList(),
)
