package pl.barometr.alerts.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.barometr.identity.api.UserId
import java.security.Principal
import java.time.ZoneId

/**
 * How often, in whose time, and when not at all.
 *
 * A `GET` for somebody who has never chosen returns the default rather than a 404: the
 * question "when will I hear from you" always has an answer, and making the client
 * invent one is how the client's idea of the default drifts from this one.
 */
@RestController
@RequestMapping("/api/v1/alerts/preferences")
class DeliveryPreferenceController(private val preferences: DeliveryPreferences) {

    @GetMapping
    fun preference(caller: Principal): PreferenceResponse =
        describe(preferences.forOwner(readerOf(caller)))

    /** Stated whole rather than patched: a cadence is one sentence, not a set of flags. */
    @PutMapping
    fun set(caller: Principal, @Valid @RequestBody request: PreferenceRequest): PreferenceResponse {
        val owner = readerOf(caller)

        return describe(preferences.set(owner, request.asPreference(owner)))
    }

    private fun describe(preference: DeliveryPreference) = PreferenceResponse(
        mode = preference.mode.wireName,
        atHour = preference.atHour,
        onWeekday = preference.onWeekday,
        zone = preference.zone.id,
        quietFrom = preference.quiet?.from,
        quietTo = preference.quiet?.to,
    )

    data class PreferenceRequest(
        @field:NotBlank
        val mode: String,
        @field:Min(0)
        @field:Max(23)
        val atHour: Int? = null,
        @field:Min(1)
        @field:Max(7)
        val onWeekday: Int? = null,
        @field:NotBlank
        val zone: String = DeliveryPreference.DEFAULT_ZONE.id,
        @field:Min(0)
        @field:Max(23)
        val quietFrom: Int? = null,
        @field:Min(0)
        @field:Max(23)
        val quietTo: Int? = null,
    ) {
        /**
         * Everything the `CHECK` constraints would refuse is refused here first, and
         * with a code rather than a constraint name: a weekly digest with no weekday is
         * not a server fault, it is somebody sending half a sentence.
         */
        fun asPreference(owner: UserId): DeliveryPreference {
            val chosen = DeliveryMode.of(mode.trim().lowercase()) ?: throw InvalidCadenceException(mode)
            val zoneId = runCatching { ZoneId.of(zone) }.getOrNull() ?: throw InvalidCadenceException(zone)

            // The value type's own `require`s are the definition of a coherent cadence;
            // restating them here would be a second copy to keep in step.
            return try {
                DeliveryPreference(
                    owner = owner,
                    mode = chosen,
                    atHour = atHour,
                    onWeekday = onWeekday,
                    zone = zoneId,
                    quiet = quietHours(),
                )
            } catch (_: IllegalArgumentException) {
                throw InvalidCadenceException("$mode at $atHour on $onWeekday")
            }
        }

        /** Both bounds or neither: half of a quiet period is not a quiet period. */
        private fun quietHours(): QuietHours? = when {
            quietFrom == null && quietTo == null -> null
            quietFrom == null || quietTo == null -> throw InvalidCadenceException("$quietFrom-$quietTo")
            else -> QuietHours(quietFrom, quietTo)
        }
    }

    data class PreferenceResponse(
        val mode: String,
        val atHour: Int?,
        val onWeekday: Int?,
        val zone: String,
        val quietFrom: Int?,
        val quietTo: Int?,
    )
}
