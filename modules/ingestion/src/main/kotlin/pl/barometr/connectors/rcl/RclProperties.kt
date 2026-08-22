package pl.barometr.connectors.rcl

import org.springframework.boot.context.properties.ConfigurationProperties
import pl.barometr.http.RobotsPolicy
import java.net.URI

/**
 * Configuration for the RCL connector.
 *
 * RCL publishes no API, so this source is read out of HTML — which makes the
 * selectors part of the configuration rather than part of the code. A layout
 * change then costs a YAML edit and a redeploy, not a release. The same
 * arrangement is what makes the BIP framework tractable later, where a handful of
 * adapters have to cover thousands of municipal sites that differ only in markup.
 */
@ConfigurationProperties(prefix = "app.connectors.rcl")
data class RclProperties(
    val baseUrl: URI = URI.create("https://legislacja.rcl.gov.pl"),

    /**
     * Deliberately slow. One request every five seconds is a rate a legislative
     * registry will not notice as load, and the archive is worth more than a fast
     * first sync — a five-year replay finishing in three days instead of one costs
     * nothing that matters.
     */
    val requestsPerSecond: Double = 0.2,

    /** Rows per index page. RPL offers 10, 50, 100 and "all"; 100 is the largest sane one. */
    val pageSize: Int = RclWalkSettings.DEFAULT_PAGE_SIZE,

    /** Index pages a single backfill call reads before its cursor becomes durable. */
    val pagesPerChunk: Int = RclWalkSettings.DEFAULT_PAGES_PER_CHUNK,

    /**
     * Levels of catalog to descend. Two reaches the documents; one stops at a
     * draft's stages and costs roughly a fifth as many requests. See
     * [RclWalkSettings] for what the difference means for a full replay.
     */
    val catalogDepth: Int = RclWalkSettings.DEFAULT_CATALOG_DEPTH,

    /**
     * Whether catalog pages are followed to the files filed under them — the draft
     * texts, the impact assessments, the tables of comments. See
     * [RclWalkSettings.fetchAttachments] for what turning it off gives up.
     */
    val fetchAttachments: Boolean = RclWalkSettings.DEFAULT_FETCH_ATTACHMENTS,

    val robots: RobotsSetting = RobotsSetting(),
    val selectors: RclSelectors = RclSelectors(),
) {

    /**
     * The walk's shape, as one value.
     *
     * How often the source is read is deliberately absent: that is registry data
     * (`sources.source.refresh_interval`), which the dispatcher reads and an
     * operator can change without a deployment. A second copy here would only be a
     * number nobody consults.
     */
    fun walkSettings(): RclWalkSettings =
        RclWalkSettings(pageSize, pagesPerChunk, catalogDepth, fetchAttachments)

    /**
     * Whether this source's robots.txt is honoured.
     *
     * As of writing, `legislacja.rcl.gov.pl/robots.txt` is `Disallow: /` for every
     * agent, so with [Mode.RESPECT] the connector reads nothing at all. Overriding
     * that is a decision with legal weight, which is why [legalBasis] is mandatory
     * and why the override announces itself in the log on every start rather than
     * sitting quietly in a file.
     */
    data class RobotsSetting(
        val mode: Mode = Mode.RESPECT,
        /**
         * The ground the override stands on — a statutory right of access, or
         * permission from RCL. Recorded here so that it is answerable: if the
         * source ever asks, the answer exists in writing.
         */
        val legalBasis: String = "",
    ) {
        enum class Mode { RESPECT, EXEMPT }

        fun toPolicy(): RobotsPolicy = when (mode) {
            Mode.RESPECT -> RobotsPolicy.Respect
            // Throws at startup when the basis is missing or a placeholder, so a
            // misconfigured exemption fails loudly instead of crawling quietly.
            Mode.EXEMPT -> RobotsPolicy.Exempt(legalBasis)
        }
    }
}
