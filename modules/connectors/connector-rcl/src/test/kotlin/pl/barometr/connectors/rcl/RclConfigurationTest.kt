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

    @Test
    fun `the site can be walked while the catalog step is still unwritten`() {
        val selectors = RclSelectors()

        // Two different questions. Every page type can be reached and archived;
        // what is missing is the step from a stage to the PDFs filed under it,
        // which needs a saved catalog page nobody has captured yet.
        assertTrue(selectors.canWalkSite)
        assertFalse(selectors.isConfigured)
        assertEquals(
            listOf("catalog.documentLink", "catalog.documentRow", "catalog.documentTitle"),
            selectors.missingFields(),
        )
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
