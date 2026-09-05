package pl.barometr.identity.internal.user

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Where the address database is, bound from the `app.identity.geoip` block.
 *
 * Blank by default and blank on most deployments: the file is licensed separately from
 * this software, so shipping one is not something the build can do. With no path, the
 * session list simply shows addresses without a place beside them — which is a feature
 * missing rather than a feature broken.
 */
@ConfigurationProperties("app.identity.geoip")
data class GeoIpProperties(
    /** Path to a MaxMind `.mmdb` file — the city or the country database, either works. */
    val databasePath: String = "",
)
