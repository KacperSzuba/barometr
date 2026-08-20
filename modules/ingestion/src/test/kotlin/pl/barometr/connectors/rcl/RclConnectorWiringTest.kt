package pl.barometr.connectors.rcl

import org.junit.jupiter.api.Test
import pl.barometr.http.HttpFetch
import pl.barometr.http.HttpOutcome
import pl.barometr.ingestion.api.BackfillConnector
import pl.barometr.ingestion.api.IncrementalConnector
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * That the configured connector is the one that actually runs.
 *
 * The modularity test verifies module structure without starting a context, so
 * nothing else in this build would notice a bean wired to defaults instead of to
 * configuration — a connector politely ignoring the pace it was given, which is
 * precisely the setting this source is most sensitive about.
 */
class RclConnectorWiringTest {

    private val configuration = RclConnectorConfiguration()

    /**
     * The settings asserted here are the ones that change what the connector does:
     * how many rows a page asks for, how many pages a chunk reads before its cursor
     * is committed, and how deep the catalog walk goes. The previous version of this
     * test asserted a declared rate and interval instead — values no part of the
     * runtime read, so it certified wiring that did not exist.
     */
    @Test
    fun `the connector walks with the configured settings rather than the defaults`() {
        val properties = RclProperties(pageSize = 25, pagesPerChunk = 3, catalogDepth = 1)

        val connector = configuration.rclConnector(siteClient(), properties)

        assertEquals(RclConnector.ID, connector.id)
        assertEquals(RclWalkSettings(pageSize = 25, pagesPerChunk = 3, catalogDepth = 1), connector.settings)
    }

    /** What the connector supports is the set of interfaces it implements. */
    @Test
    fun `the connector reads both incrementally and by backfill`() {
        val connector = configuration.rclConnector(siteClient(), RclProperties())

        assertTrue(connector is IncrementalConnector)
        assertTrue(connector is BackfillConnector)
    }

    /**
     * Unwritten catalog selectors leave a connector that still archives every page
     * and only stops short of following attachments. Reduced, not broken — so it
     * starts, and says so in the log.
     */
    @Test
    fun `missing catalog selectors do not stop the connector from starting`() {
        val connector = configuration.rclConnector(siteClient(), RclProperties())

        assertTrue(connector.partitions(java.time.LocalDate.MIN, java.time.LocalDate.MAX).isNotEmpty())
    }

    /**
     * Blanking a selector the walk depends on is the opposite case: it leaves a
     * connector that reads nothing while reporting healthy runs, so it refuses to
     * start rather than archiving silence.
     */
    @Test
    fun `blanking a selector the walk needs refuses to start`() {
        val properties = RclProperties(
            selectors = RclSelectors(listing = RclSelectors.Listing(row = "")),
        )

        val failure = assertFailsWith<IllegalStateException> {
            configuration.rclConnector(siteClient(), properties)
        }
        assertTrue(failure.message!!.contains("listing.row"))
    }

    /**
     * The robots exemption is built while the application context is coming up, so
     * an override with no written basis stops the deployment instead of quietly
     * crawling a site that asked us not to.
     */
    @Test
    fun `an exemption without a written basis stops the application starting`() {
        val properties = RclProperties(
            robots = RclProperties.RobotsSetting(
                mode = RclProperties.RobotsSetting.Mode.EXEMPT,
                legalBasis = "",
            ),
        )

        assertFailsWith<IllegalArgumentException> { properties.robots.toPolicy() }
    }

    private fun siteClient() = RclSiteClient(
        object : pl.barometr.http.SourceHttpClient {
            override fun fetch(request: HttpFetch): HttpOutcome =
                HttpOutcome.Failed(503, "not called in this test")
        },
    )
}
