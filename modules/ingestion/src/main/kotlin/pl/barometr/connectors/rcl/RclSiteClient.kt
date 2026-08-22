package pl.barometr.connectors.rcl

import org.jsoup.Jsoup
import pl.barometr.http.HttpFetch
import pl.barometr.http.HttpOutcome
import pl.barometr.http.SourceHttpClient
import pl.barometr.ingestion.api.SourceAccessDeniedException
import pl.barometr.ingestion.api.SourceFetchException
import java.net.URI

/**
 * Fetches RPL pages and files, and hands back something the walk can act on.
 *
 * Everything about *how* this source is reached lives here — the fact that its pages
 * are HTML rather than an API, that the encoding must be forced, that a refusal is not
 * a failure. Pacing, retries, conditional requests and the robots gate are not
 * repeated here: they belong to the shared HTTP layer, which is the only reason a
 * source read at one request per five seconds needs no scheduling code of its own.
 */
class RclSiteClient(private val httpClient: SourceHttpClient) {

    /** Null when the source answered 304 and there is nothing new to archive. */
    fun readPage(url: URI, etag: String? = null): RclPage? = read(url, etag) { fetched ->
        RclPage(
            url = url,
            html = fetched.body,
            // Charset stated rather than sniffed: RPL declares UTF-8 in a meta tag,
            // and letting jsoup guess turns every "ł" in a draft title into a
            // replacement character on a JVM with a non-UTF-8 default.
            document = Jsoup.parse(fetched.body.inputStream(), Charsets.UTF_8.name(), url.toString()),
            etag = fetched.etag,
            lastModified = fetched.lastModified,
        )
    }

    /**
     * A file filed under a stage — a draft, a justification, a table of comments.
     *
     * Deliberately not parsed. What comes back is a PDF or a Word document, and the
     * archive's promise is to keep exactly the bytes the source served; interpreting
     * them is a later step, taken from the archive rather than from the wire.
     */
    fun readAttachment(url: URI, etag: String? = null): RclAttachment? = read(url, etag) { fetched ->
        RclAttachment(
            url = url,
            bytes = fetched.body,
            contentType = fetched.contentType,
            etag = fetched.etag,
            lastModified = fetched.lastModified,
        )
    }

    /**
     * One translation of an HTTP outcome into this connector's vocabulary, shared by
     * both readers: a refusal is not worth retrying, a failure is, and nothing above
     * should have to learn two names for each.
     */
    private fun <T> read(url: URI, etag: String?, interpret: (HttpOutcome.Fetched) -> T): T? =
        when (val outcome = httpClient.fetch(HttpFetch(url = url, etag = etag))) {
            is HttpOutcome.Fetched -> interpret(outcome)

            HttpOutcome.NotModified -> null

            is HttpOutcome.Refused ->
                throw SourceAccessDeniedException(url.path, "${outcome.reason}: ${outcome.detail}")

            is HttpOutcome.Failed ->
                throw SourceFetchException(url.path, "${outcome.statusCode ?: "-"} ${outcome.detail}")
        }
}
