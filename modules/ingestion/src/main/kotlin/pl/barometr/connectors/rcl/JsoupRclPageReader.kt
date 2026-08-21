package pl.barometr.connectors.rcl

import org.jsoup.Jsoup
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.connectors.rcl.api.RclProjectCard
import java.io.ByteArrayInputStream

/**
 * Reads an archived RPL page with the same parser and the same selectors the crawl
 * uses.
 *
 * The whole point of publishing the port: one description of the site's markup, in
 * configuration, shared by the crawl that walks it and by everything that later reads
 * what the crawl stored.
 *
 * The charset is left to jsoup rather than assumed. RPL declares it in a meta tag, and
 * a page decoded as the wrong one loses exactly the characters Polish titles are made
 * of.
 */
class JsoupRclPageReader(private val cards: RclProjectCardParser) : RclPageReader {

    override fun readProjectCard(page: ByteArray): RclProjectCard? =
        ByteArrayInputStream(page).use { bytes ->
            cards.readProjectCard(Jsoup.parse(bytes, null, ""))
        }
}
