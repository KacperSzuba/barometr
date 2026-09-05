package pl.barometr.identity.internal.user

import org.slf4j.LoggerFactory
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Turns an address into something like `Warszawa, PL`.
 *
 * **It never fails a request.** A missing record, an unreadable database, an address that
 * is not one — all of them are a blank in a list, which is exactly what a guess this
 * system cannot make should look like. Somebody reading their device list is not helped
 * by an error where a city would be.
 *
 * **A private address is left blank on purpose.** `10.x` and `192.168.x` place a session
 * inside somebody's own office, which the database cannot know anything about and which
 * would print as a confident-looking wrong answer. The same goes for loopback, which is
 * every request on a developer's machine.
 */
class MaxMindLocations(private val lookup: GeoLookup) : ApproximateLocations {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun locate(clientIp: String?): String? {
        val address = literalAddress(clientIp) ?: return null
        if (isPrivate(address)) return null

        val record = try {
            lookup.recordFor(address)
        } catch (unreadable: Exception) {
            // Caught rather than propagated: a database that cannot be read is a label
            // nobody gets, not a sign-in nobody can make. Logged at warn because a
            // deployment that configured one and gets nothing should be able to find out
            // why without reading this code.
            log.warn("Address database could not be read: {}", unreadable.message)
            return null
        }

        return label(record ?: return null)
    }

    /**
     * The city where the database has one, and the country either way.
     *
     * Polish first: this is read by somebody deciding whether they were in Warsaw last
     * Tuesday, and `Warszawa` is the name they answer that question in.
     */
    private fun label(record: GeoRecord): String? {
        val country = record.country?.isoCode
        val city = record.city?.names?.let { it["pl"] ?: it["en"] }

        return when {
            city != null && country != null -> "$city, $country"
            city != null -> city
            else -> country
        }
    }

    /**
     * A literal address, never a hostname: `ofLiteral` parses without asking DNS, so a
     * header somebody controls cannot turn a session list into a name lookup.
     */
    private fun literalAddress(clientIp: String?): InetAddress? =
        clientIp?.takeIf { it.isNotBlank() }?.let { runCatching { InetAddress.ofLiteral(it) }.getOrNull() }

    private fun isPrivate(address: InetAddress): Boolean =
        address.isLoopbackAddress ||
            address.isAnyLocalAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            (address is Inet4Address && address.isPrivateIpv4())

    /**
     * `isSiteLocalAddress` misses `100.64.0.0/10`, the range carrier-grade NAT uses —
     * which is most mobile traffic in Poland, and exactly the kind of address a database
     * places confidently in the wrong city.
     */
    private fun Inet4Address.isPrivateIpv4(): Boolean {
        val bytes = address.map { it.toInt() and 0xff }

        return bytes[0] == 100 && bytes[1] in 64..127
    }
}
