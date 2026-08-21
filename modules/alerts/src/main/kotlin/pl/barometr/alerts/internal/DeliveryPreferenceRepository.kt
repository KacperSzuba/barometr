package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.alerts.internal.jooq.tables.references.DELIVERY_PREFERENCE
import pl.barometr.identity.api.UserId
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * How often each person wants hearing from us. SQL only.
 */
@Repository
class DeliveryPreferenceRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /** Null when they have never said, which is not the same as choosing the default. */
    fun findFor(owner: UserId): DeliveryPreference? =
        dsl.selectFrom(DELIVERY_PREFERENCE)
            .where(DELIVERY_PREFERENCE.OWNER_ID.eq(owner.value))
            .fetchOne {
                DeliveryPreference(
                    owner = owner,
                    mode = DeliveryMode.of(it.mode!!) ?: error("stored mode '${it.mode}'"),
                    atHour = it.atHour,
                    onWeekday = it.onWeekday,
                    zone = ZoneId.of(it.zone!!),
                    quiet = it.quietFrom?.let { from -> QuietHours(from, it.quietTo!!) },
                )
            }

    /** One row per person, replaced whole: a preference is one statement, not a patch. */
    fun save(preference: DeliveryPreference): DeliveryPreference {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

        dsl.insertInto(DELIVERY_PREFERENCE)
            .set(DELIVERY_PREFERENCE.OWNER_ID, preference.owner.value)
            .set(DELIVERY_PREFERENCE.MODE, preference.mode.wireName)
            .set(DELIVERY_PREFERENCE.AT_HOUR, preference.atHour)
            .set(DELIVERY_PREFERENCE.ON_WEEKDAY, preference.onWeekday)
            .set(DELIVERY_PREFERENCE.ZONE, preference.zone.id)
            .set(DELIVERY_PREFERENCE.QUIET_FROM, preference.quiet?.from)
            .set(DELIVERY_PREFERENCE.QUIET_TO, preference.quiet?.to)
            .set(DELIVERY_PREFERENCE.CREATED_AT, now)
            .set(DELIVERY_PREFERENCE.UPDATED_AT, now)
            .onConflict(DELIVERY_PREFERENCE.OWNER_ID)
            .doUpdate()
            .set(DELIVERY_PREFERENCE.MODE, preference.mode.wireName)
            .set(DELIVERY_PREFERENCE.AT_HOUR, preference.atHour)
            .set(DELIVERY_PREFERENCE.ON_WEEKDAY, preference.onWeekday)
            .set(DELIVERY_PREFERENCE.ZONE, preference.zone.id)
            .set(DELIVERY_PREFERENCE.QUIET_FROM, preference.quiet?.from)
            .set(DELIVERY_PREFERENCE.QUIET_TO, preference.quiet?.to)
            .set(DELIVERY_PREFERENCE.UPDATED_AT, now)
            .execute()

        return preference
    }
}
