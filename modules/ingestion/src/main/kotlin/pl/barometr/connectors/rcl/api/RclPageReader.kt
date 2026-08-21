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
}
