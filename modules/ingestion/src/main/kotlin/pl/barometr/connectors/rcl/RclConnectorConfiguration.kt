package pl.barometr.connectors.rcl

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.http.HttpPolicy
import pl.barometr.http.SourceHttpClientFactory

@Configuration
class RclConnectorConfiguration {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun rclSiteClient(
        httpClientFactory: SourceHttpClientFactory,
        properties: RclProperties,
    ): RclSiteClient = RclSiteClient(
        httpClientFactory.create(
            HttpPolicy(
                requestsPerSecond = properties.requestsPerSecond,
                // Constructing this throws when an exemption carries no written
                // basis, so a misconfigured override stops the application here
                // rather than crawling quietly.
                robots = properties.robots.toPolicy(),
            ),
        ),
    )

    /**
     * The one thing this connector publishes. Everything deriving from RPL's archived
     * pages goes through it, so the selectors have one home rather than three.
     */
    @Bean
    fun rclPageReader(properties: RclProperties): RclPageReader = JsoupRclPageReader(
        RclProjectCardParser(properties.selectors.projectCard),
        RclCatalogParser(properties.selectors.catalog),
        RclChangeRegisterParser(properties.selectors.changeRegister),
    )

    @Bean
    fun rclConnector(
        site: RclSiteClient,
        properties: RclProperties,
    ): RclConnector {
        reportUnwrittenSelectors(properties.selectors)

        return RclConnector(
            site = site,
            pages = RclUrls(properties.baseUrl),
            listings = RclListingParser(properties.selectors.listing),
            cards = RclProjectCardParser(properties.selectors.projectCard),
            registers = RclChangeRegisterParser(properties.selectors.changeRegister),
            catalogs = RclCatalogParser(properties.selectors.catalog),
            settings = properties.walkSettings(),
        )
    }

    /**
     * Says at startup what this connector cannot yet do.
     *
     * A warning rather than a failure, because a blank catalog group means something
     * different from every other blank. Without it the connector still archives every
     * page whole and only stops short of following the files filed under a stage —
     * reduced, not broken, and recoverable later from the archive itself. Blanking
     * anything else leaves a connector that walks nothing, which is worth refusing to
     * start over.
     *
     * With the defaults nothing is reported: the catalog selectors are written now,
     * against a captured page. What this still guards is a YAML override that blanks
     * one of them.
     */
    private fun reportUnwrittenSelectors(selectors: RclSelectors) {
        val missing = selectors.missingFields()
        if (missing.isEmpty()) return

        check(selectors.canWalkSite) {
            "RCL selectors needed to walk the site are blank: " +
                missing.filterNot { it.startsWith("catalog.") }.joinToString()
        }

        log.warn(
            "RCL catalog selectors are unset ({}), so stage catalogs are archived as " +
                "HTML but the files filed under them are not fetched.",
            missing.joinToString(),
        )
    }
}
