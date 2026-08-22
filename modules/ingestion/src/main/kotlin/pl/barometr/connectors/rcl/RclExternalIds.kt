package pl.barometr.connectors.rcl

/**
 * How an RPL page is addressed in our archive.
 *
 * In one place because these strings are the idempotency key: change how a project
 * card is addressed and every card in the archive re-ingests as if it were new.
 *
 * The draft's kind is folded into the id even though RPL's own URLs leave it out.
 * That is what makes a completeness audit possible: the archive can be counted one
 * partition at a time, against the tally RPL prints for that kind. It is safe to
 * bake in because a draft's kind is intrinsic rather than a status — a bill never
 * becomes a regulation — so the id it produces is stable for the document's life.
 */
object RclExternalIds {

    fun project(type: RclProjectType, projectId: String) =
        ingestionId("${projectPrefix(type)}$projectId")

    fun projectChangeRegister(type: RclProjectType, projectId: String) =
        ingestionId("${projectPrefix(type)}$projectId/rejestr")

    fun catalog(type: RclProjectType, projectId: String, catalogId: String) =
        ingestionId("${projectPrefix(type)}$projectId/katalog/$catalogId")

    fun catalogChangeRegister(type: RclProjectType, projectId: String, catalogId: String) =
        ingestionId("${projectPrefix(type)}$projectId/katalog/$catalogId/rejestr")

    /**
     * A file filed under a catalog, addressed by RPL's own id for it.
     *
     * **Without its extension, deliberately.** The number is what RPL calls the
     * document; the format is a property of the bytes, already recorded as the
     * payload's kind and the blob's media type. So a ministry replacing a `.doc` with
     * the `.docx` of the same document lands where it should — as a second version of
     * one document, which is exactly the event this archive exists to catch — instead
     * of as two unrelated documents that happen to share a name.
     *
     * The catalog is the one the file is filed in, taken from its own href. A file
     * shows up on its parent's page as well as its own, and addressing it by the page
     * it was seen on would archive the same bytes twice under two ids.
     */
    fun attachment(type: RclProjectType, projectId: String, catalogId: String, documentId: String) =
        ingestionId("${projectPrefix(type)}$projectId/katalog/$catalogId/dokument/$documentId")

    /**
     * Counting prefix for one partition's project cards.
     *
     * Pairs with the archive's "directly under" count, so the registers, catalog pages
     * and filed documents nested beneath a card — `…/12409051/rejestr`,
     * `…/12409051/katalog/13196866`, `…/katalog/13196867/dokument/770751` — are not
     * mistaken for cards of their own.
     */
    fun projectPrefix(type: RclProjectType): String = "projekt/${type.slug}/"

    private fun ingestionId(value: String) = pl.barometr.ingestion.api.ExternalId(value)
}
