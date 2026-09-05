package pl.barometr.identity.internal.user

import com.maxmind.db.Reader
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.File

/**
 * Opens the address database, or decides there is none.
 *
 * **A configured path that cannot be opened stops the application.** The alternative is a
 * deployment that believes it has locations and quietly has none, which is the failure
 * nobody notices until somebody asks why the list has been blank for a month. No path at
 * all is a different thing and perfectly fine: the feature is off.
 *
 * The file is memory-mapped and read for the life of the process, which is what makes a
 * lookup a few microseconds and not an I/O call per session row.
 */
@Configuration
class GeoIpConfiguration {

    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun approximateLocations(properties: GeoIpProperties): ApproximateLocations {
        val path = properties.databasePath.trim()
        if (path.isEmpty()) {
            log.info("No address database configured; sessions will be listed without a location")
            return UnknownLocations
        }

        val database = File(path)
        check(database.isFile) { "app.identity.geoip.database-path does not name a file: $path" }

        val reader = Reader(database)
        log.info("Address database open: {} ({})", path, reader.metadata.databaseType)

        return MaxMindLocations { address -> reader.get(address, GeoRecord::class.java) }
    }
}
