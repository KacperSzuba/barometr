package pl.barometr.identity.internal.user

import com.maxmind.db.MaxMindDbConstructor
import com.maxmind.db.MaxMindDbParameter

/**
 * As much of a MaxMind record as a session list needs: a country, and a city if the
 * database has one.
 *
 * Typed rather than read as nested maps, which is the same rule the jsonb columns follow:
 * a cast over `Map<*, *>` fails far from the cause, and the file's shape is a contract
 * this can state.
 */
data class GeoRecord @MaxMindDbConstructor constructor(
    @param:MaxMindDbParameter(name = "country") val country: GeoCountry?,
    @param:MaxMindDbParameter(name = "city") val city: GeoCity?,
)
