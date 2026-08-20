package pl.barometr.connectors.rcl

import org.jsoup.Jsoup
import pl.barometr.ingestion.api.SourceAccessDeniedException
import pl.barometr.ingestion.api.SourceFetchException
import pl.barometr.http.HttpFetch
import pl.barometr.http.HttpOutcome
import pl.barometr.http.SourceHttpClient
import java.net.URI

/**
 * Fetches RPL pages and hands back parsed documents.
 *
 * Everything about *how* this source is reached lives here — the fact that it is
 * HTML rather than an API, that the encoding must be forced, that a refusal is not
 * a failure. Pacing, retries, conditional requests and the robots gate are not
 * repeated here: they belong to the shared HTTP layer, which is the only reason a
 * source read at one request per five seconds needs no scheduling code of its own.
 */
class RclSiteClient(private val httpClient: SourceHttpClient) {

    /** Null when the source answered 304 and there is nothing new to archive. */
    fun readPage(url: URI, etag: String? = null): RclPage? {
        return when (val outcome = httpClient.fetch(HttpFetch(url = url, etag = etag))) {
            is HttpOutcome.Fetched -> RclPage(
                url = url,
                html = outcome.body,
                // Charset stated rather than sniffed: RPL declares UTF-8 in a meta
                // tag, and letting jsoup guess turns every "ł" in a draft title
                // into a replacement character on a JVM with a non-UTF-8 default.
                document = Jsoup.parse(
                    outcome.body.inputStream(),
                    Charsets.UTF_8.name(),
                    url.toString(),
                ),
                etag = outcome.etag,
                lastModified = outcome.lastModified,
            )

            HttpOutcome.NotModified -> null

            is HttpOutcome.Refused ->
                throw SourceAccessDeniedException(url.path, "${outcome.reason}: ${outcome.detail}")

            is HttpOutcome.Failed ->
                throw SourceFetchException(url.path, "${outcome.statusCode ?: "-"} ${outcome.detail}")
        }
    }
}
