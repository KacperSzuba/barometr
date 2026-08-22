package pl.barometr.connectors.rcl

import org.junit.jupiter.api.Test
import pl.barometr.http.RobotsPolicy
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RclConfigurationTest {

    @Test
    fun `respecting robots is the default and needs no justification`() {
        assertEquals(RobotsPolicy.Respect, RclProperties().robots.toPolicy())
    }

    /**
     * The override cannot be switched on quietly. Overriding a site's stated refusal
     * is a decision somebody has to own, so the configuration refuses to express it
     * without a written reason — and fails at startup rather than crawling first and
     * raising questions later.
     */
    @Test
    fun `an exemption without a stated basis fails at startup`() {
        val unjustified = RclProperties.RobotsSetting(mode = RclProperties.RobotsSetting.Mode.EXEMPT)

        val failure = assertFailsWith<IllegalArgumentException> { unjustified.toPolicy() }
        assertTrue(failure.message!!.contains("written legal basis"))
    }

    @Test
    fun `a placeholder is not a basis`() {
        val placeholder = RclProperties.RobotsSetting(
            mode = RclProperties.RobotsSetting.Mode.EXEMPT,
            legalBasis = "TODO",
        )

        assertFailsWith<IllegalArgumentException> { placeholder.toPolicy() }
    }

    @Test
    fun `a stated basis travels with the policy`() {
        val basis = "Ustawa z 6.09.2001 o dostępie do informacji publicznej, art. 2 ust. 1"
        val setting = RclProperties.RobotsSetting(
            mode = RclProperties.RobotsSetting.Mode.EXEMPT,
            legalBasis = basis,
        )

        assertEquals(RobotsPolicy.Exempt(basis), setting.toPolicy())
    }

    /**
     * Both questions answer yes now. The catalog group used to be the standing
     * exception — every page reachable and archivable, but the step from a stage to
     * the files filed under it unwritten for want of a captured page — and this test
     * is what recorded it. It records the closing of it instead.
     */
    @Test
    fun `the whole walk is configured, catalog step included`() {
        val selectors = RclSelectors()

        assertTrue(selectors.canWalkSite)
        assertTrue(selectors.isConfigured)
        assertEquals(emptyList(), selectors.missingFields())
    }

    /**
     * Blanking a catalog selector is the one kind of missing configuration that does
     * not stop the connector: it still archives every page whole, and the files can
     * be fetched later from links the archive already holds. Everything else missing
     * leaves a connector that walks nothing.
     */
    @Test
    fun `a blanked catalog selector leaves a reduced connector, not a broken one`() {
        val selectors = RclSelectors(catalog = RclSelectors.Catalog(documentLink = ""))

        assertTrue(selectors.canWalkSite)
        assertFalse(selectors.isConfigured)
        assertEquals(listOf("catalog.documentLink"), selectors.missingFields())
    }

    /**
     * The defaults are real selectors now, so the failure mode this guards is the
     * opposite of the original one: not a connector shipped unconfigured, but a
     * YAML override that blanks a field and leaves a connector that runs happily
     * and archives nothing.
     */
    @Test
    fun `blanking a selector in configuration is reported as missing`() {
        val selectors = RclSelectors(
            listing = RclSelectors.Listing(row = ""),
        )

        assertFalse(selectors.canWalkSite)
        assertTrue(selectors.missingFields().contains("listing.row"))
    }
}
