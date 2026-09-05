package pl.barometr.identity.internal.user

import com.maxmind.db.MaxMindDbConstructor
import com.maxmind.db.MaxMindDbParameter

/**
 * A city, in whichever languages the database carries.
 *
 * Polish first and English second when it is read: this is shown to somebody in Polish,
 * and `Warszawa` is the name they would recognise faster than `Warsaw`.
 */
data class GeoCity @MaxMindDbConstructor constructor(
    @param:MaxMindDbParameter(name = "names") val names: Map<String, String>?,
)
