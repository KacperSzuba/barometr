package pl.barometr.connectors.rcl.api

/**
 * Reads an archived RPL page into the model above.
 *
 * A port rather than the parser itself, because reading one of these pages needs the
 * selectors that describe the site's markup, and those are configuration the connector
 * owns. A caller deriving from the archive should not have to know that, let alone
 * carry a second copy of it.
 */
interface RclPageReader {

    /** Null when the bytes are not a draft's page — an error page, or another view. */
    fun readProjectCard(page: ByteArray): RclProjectCard?

    /**
     * A stage's catalog: its folders and the files in them.
     *
     * Returns an empty page rather than null for bytes that are not a catalog, because
     * "no files here" is a real and common answer — a stage nothing has been filed
     * under yet renders exactly like one whose folders are empty.
     */
    fun readCatalog(page: ByteArray): RclCatalogPage
}
