package pl.barometr.identity.internal.user

import com.maxmind.db.MaxMindDbConstructor
import com.maxmind.db.MaxMindDbParameter

/** The two-letter code, which is the part of a country that never needs translating. */
data class GeoCountry @MaxMindDbConstructor constructor(
    @param:MaxMindDbParameter(name = "iso_code") val isoCode: String?,
)
