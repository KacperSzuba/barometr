package pl.barometr.alerts.internal

import org.springframework.stereotype.Service
import pl.barometr.identity.api.UserId

/**
 * What somebody's cadence is, including when they have never said.
 *
 * The default lives here rather than in the controller so that the endpoint and the run
 * that closes windows cannot answer the question differently.
 */
@Service
class DeliveryPreferences(private val preferences: DeliveryPreferenceRepository) {

    fun forOwner(owner: UserId): DeliveryPreference =
        preferences.findFor(owner) ?: DeliveryPreference.defaultFor(owner)

    fun set(owner: UserId, preference: DeliveryPreference): DeliveryPreference =
        preferences.save(preference.copy(owner = owner))
}
