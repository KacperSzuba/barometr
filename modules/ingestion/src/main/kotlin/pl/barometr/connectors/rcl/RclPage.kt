package pl.barometr.connectors.rcl

import org.jsoup.nodes.Document
import java.net.URI

/**
 * A page as RPL served it: the bytes for the archive, and a parse tree for
 * deciding where to go next.
 *
 * Both, because they answer different questions and only one of them is a fact.
 * The bytes are what gets stored and content-addressed; the parse tree is this
 * connector's reading of them, which will be wrong the day RPL changes its markup.
 * Keeping the original means that day costs a re-parse of the archive rather than
 * a re-crawl of the site.
 */
class RclPage(
    val url: URI,
    val html: ByteArray,
    val document: Document,
    val etag: String?,
    val lastModified: String?,
)
