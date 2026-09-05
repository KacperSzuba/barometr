package pl.barometr.identity.internal.user

import org.junit.jupiter.api.Test
import java.net.InetAddress
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Turning an address into a place, and — mostly — deciding not to.
 *
 * The lookup itself is a library call over a file the deployment supplies, and is behind
 * [GeoLookup] for exactly that reason: what this codebase owns is everything around it,
 * which is where a wrong or over-confident answer would come from.
 */
class MaxMindLocationsTest {

    @Test
    fun `a city and a country read the way somebody in Poland would say them`() {
        val locations = MaxMindLocations { record(city = mapOf("pl" to "Warszawa", "en" to "Warsaw"), country = "PL") }

        assertEquals("Warszawa, PL", locations.locate("203.0.113.7"))
    }

    @Test
    fun `English is the fallback, because a database may carry nothing else`() {
        val locations = MaxMindLocations { record(city = mapOf("en" to "Gdansk"), country = "PL") }

        assertEquals("Gdansk, PL", locations.locate("203.0.113.7"))
    }

    @Test
    fun `a country database answers with the country alone`() {
        val locations = MaxMindLocations { record(city = null, country = "DE") }

        assertEquals("DE", locations.locate("203.0.113.7"))
    }

    @Test
    fun `an address the database has nothing for is a blank, not a guess`() {
        val locations = MaxMindLocations { null }

        assertNull(locations.locate("203.0.113.7"))
    }

    /**
     * A private address places a session inside somebody's own office, which the database
     * cannot know anything about and would print as a confident-looking wrong answer.
     */
    @Test
    fun `a private or local address is never placed`() {
        val locations = MaxMindLocations { record(city = mapOf("pl" to "Warszawa"), country = "PL") }

        listOf("127.0.0.1", "10.1.2.3", "192.168.1.10", "172.16.0.5", "::1", "fe80::1").forEach { address ->
            assertNull(locations.locate(address), "$address is not a place")
        }
    }

    /**
     * `100.64.0.0/10` is what carrier-grade NAT uses — most mobile traffic in Poland —
     * and `isSiteLocalAddress` does not cover it. A database will happily place it in
     * whichever city the carrier registered the range to.
     */
    @Test
    fun `a carrier-grade NAT address is not placed either`() {
        val locations = MaxMindLocations { record(city = mapOf("pl" to "Warszawa"), country = "PL") }

        assertNull(locations.locate("100.70.1.1"))
        assertEquals("Warszawa, PL", locations.locate("100.128.1.1"), "and the range next to it still is")
    }

    @Test
    fun `something that is not an address is not resolved, and nothing is asked of DNS`() {
        val locations = MaxMindLocations { error("nothing should be looked up") }

        assertNull(locations.locate("nie-jest-adresem.example.test"))
        assertNull(locations.locate(""))
        assertNull(locations.locate(null))
    }

    /** A label nobody gets is not a sign-in nobody can make. */
    @Test
    fun `a database that cannot be read costs a label and nothing else`() {
        val locations = MaxMindLocations { throw IllegalStateException("mmdb is corrupt") }

        assertNull(locations.locate("203.0.113.7"))
    }

    @Test
    fun `a record with nothing in it is a blank`() {
        val locations = MaxMindLocations { GeoRecord(country = null, city = null) }

        assertNull(locations.locate("203.0.113.7"))
    }

    @Test
    fun `with no database configured nothing is placed at all`() {
        assertNull(UnknownLocations.locate("203.0.113.7"))
    }

    private fun record(city: Map<String, String>?, country: String?) =
        GeoRecord(country = country?.let(::GeoCountry), city = city?.let(::GeoCity))

    /** Only to make the seam's shape explicit: it is handed one address and nothing else. */
    private fun MaxMindLocations(lookup: (InetAddress) -> GeoRecord?) = MaxMindLocations(GeoLookup(lookup))
}
