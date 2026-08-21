package pl.barometr.alerts.internal

import pl.barometr.identity.api.UserId
import java.time.ZoneId

/**
 * When somebody wants hearing from us, in their own time.
 *
 * The zone is theirs rather than the server's: a daily digest "at eight" that arrives at
 * ten because the server thinks in UTC is a broken promise, and the only place the
 * answer exists is the person who set it.
 */
data class DeliveryPreference(
    val owner: UserId,
    val mode: DeliveryMode,
    /** The local hour a daily or weekly window closes at. Null for the other modes. */
    val atHour: Int? = null,
    /** Monday is 1, matching `java.time.DayOfWeek`. Null unless weekly. */
    val onWeekday: Int? = null,
    val zone: ZoneId = DEFAULT_ZONE,
    val quiet: QuietHours? = null,
) {
    init {
        require(mode.needsHour == (atHour != null)) { "$mode does not take an hour" }
        require((mode == DeliveryMode.WEEKLY) == (onWeekday != null)) { "$mode does not take a weekday" }
    }

    companion object {
        /**
         * Everything this system reads is published in Warsaw, and the people reading it
         * work to those hours. A default that is right for almost everybody beats
         * refusing to act until somebody sets it.
         */
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

        /**
         * What somebody who has said nothing gets: everything, as it happens, with no
         * quiet hours.
         *
         * Immediate rather than daily on purpose. Somebody who has not chosen has not
         * asked to be delayed, and an alerting product that quietly holds the first
         * thing it ever finds for a day teaches its user that it does not work.
         */
        fun defaultFor(owner: UserId) = DeliveryPreference(owner, DeliveryMode.IMMEDIATE)
    }
}
