package pl.barometr.legislative.internal

import pl.barometr.ingestion.api.ExternalId

/**
 * Where in RPL's tree an archived page or file sits, read back out of the address it
 * was archived under.
 *
 * The address is the only thing that survives into this context. An event says a
 * version of some document exists; whether that document is a consultation letter or
 * a ministry's impact assessment is answered by the folder it was filed in, and the
 * folder is in the id and nowhere else.
 *
 * The format is stated here as well as in the connector's `RclExternalIds`, and that
 * duplication is the same trade `ArchivedDocumentReader` describes: the archive is the
 * contract, so a consumer deriving from it must be able to stand on the stored
 * addresses alone rather than on a connector still being there to ask.
 */
data class RclCatalogAddress(val projectId: String, val catalogId: String) {

    companion object {
        /** `projekt/ustawa/12409051/katalog/13196866` — a stage's own page. */
        private val CATALOG_PAGE = Regex("""projekt/[^/]+/([^/]+)/katalog/([^/]+)""")

        /** `projekt/ustawa/12409051/katalog/13196868/dokument/778141` — a file in a folder. */
        private val FILED_DOCUMENT = Regex("""projekt/[^/]+/([^/]+)/katalog/([^/]+)/dokument/[^/]+""")

        fun ofCatalogPage(externalId: ExternalId): RclCatalogAddress? = read(CATALOG_PAGE, externalId)

        fun ofFiledDocument(externalId: ExternalId): RclCatalogAddress? = read(FILED_DOCUMENT, externalId)

        /**
         * The same two shapes written rather than read, for a caller going the other
         * way: it holds a draft's address and a folder, and wants to know what to ask
         * the archive for.
         *
         * Here rather than beside the caller so that the format is stated once on this
         * side of the boundary — a sweep that spelled an address slightly differently
         * from the way one is parsed would find nothing, and find it silently.
         */
        fun catalogPageAt(draftAddress: String, catalogId: String) =
            ExternalId("$draftAddress/katalog/$catalogId")

        fun filedDocumentAt(draftAddress: String, catalogId: String, documentId: String) =
            ExternalId("${catalogPageAt(draftAddress, catalogId).value}/dokument/$documentId")

        /**
         * `matchEntire`, not `find`: a catalog's change register is a catalog page's
         * address with `/rejestr` on the end, and a file's is one with two more
         * segments. A prefix match would read all three as the same thing and hand a
         * register's id to a query expecting a folder's.
         */
        private fun read(shape: Regex, externalId: ExternalId): RclCatalogAddress? =
            shape.matchEntire(externalId.value)?.let {
                RclCatalogAddress(projectId = it.groupValues[1], catalogId = it.groupValues[2])
            }
    }
}
