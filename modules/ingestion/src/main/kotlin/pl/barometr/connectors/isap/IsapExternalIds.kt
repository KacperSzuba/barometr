package pl.barometr.connectors.isap

import pl.barometr.ingestion.api.ExternalId
import pl.barometr.shared.Eli

/**
 * How an act is addressed in our archive.
 *
 * The ELI itself, unadorned. It is already the canonical identifier of a Polish
 * act, unique across both journals and stable for the act's life, so a second
 * addressing scheme layered on top would only be something to keep in step with it.
 *
 * In one object regardless, because an external id is the idempotency key: change
 * how it is written and every act in the archive re-ingests as if it were new.
 */
object IsapExternalIds {

    fun act(eli: Eli): ExternalId = ExternalId(eli.value)

    /**
     * Counting prefix for one partition. Pairs with the archive count the
     * completeness audit compares against what the API declares for that year.
     */
    fun yearPrefix(publisher: String, year: Int): String = "$publisher/$year/"
}
