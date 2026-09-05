package pl.barometr.alerts.internal

import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.alerts.internal.jooq.tables.references.ALERT_DECISION
import pl.barometr.alerts.internal.jooq.tables.references.EMAIL_DELIVERY
import pl.barometr.alerts.internal.jooq.tables.references.NOTIFICATION
import java.time.Clock
import java.time.ZoneOffset

/**
 * Retention for what this context knows about people, as a thing that runs.
 *
 * A product that tells somebody about a bill every week accumulates a record of what they
 * were told and what they were not, for as long as nothing deletes it. The policy is
 * two years for notifications and a year for the rest — written down in
 * [AlertRetentionProperties] rather than here, because a data-protection register has to
 * be able to name one place where the answer is.
 *
 * Locked across the deployment and bounded per run: this deletes from the tables the
 * alert run writes to, and a sweep holding a long transaction over them at seven in the
 * morning would be the one thing standing between somebody and their digest.
 */
@Component
class AlertRetentionSweep(
    private val dsl: DSLContext,
    private val properties: AlertRetentionProperties,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.alerts.retention.sweep-interval:PT6H}", initialDelay = 900_000)
    @SchedulerLock(name = "alerts-retention")
    @Transactional
    fun deleteWhatRetentionSaysToDelete() {
        val now = clock.instant()

        val notifications = dsl.deleteFrom(NOTIFICATION)
            .where(NOTIFICATION.CREATED_AT.lt(now.minus(properties.notifications).atOffset(ZoneOffset.UTC)))
            .execute()

        val decisions = dsl.deleteFrom(ALERT_DECISION)
            .where(ALERT_DECISION.DECIDED_AT.lt(now.minus(properties.decisions).atOffset(ZoneOffset.UTC)))
            .execute()

        val deliveries = dsl.deleteFrom(EMAIL_DELIVERY)
            .where(EMAIL_DELIVERY.ATTEMPTED_AT.lt(now.minus(properties.deliveries).atOffset(ZoneOffset.UTC)))
            .execute()

        if (notifications + decisions + deliveries == 0) return

        meters.counter("alerts.retention.deleted", "category", "notification").increment(notifications.toDouble())
        meters.counter("alerts.retention.deleted", "category", "decision").increment(decisions.toDouble())
        meters.counter("alerts.retention.deleted", "category", "delivery").increment(deliveries.toDouble())
        log.info(
            "Retention: {} notification(s), {} decision(s) and {} delivery record(s) deleted",
            notifications,
            decisions,
            deliveries,
        )
    }
}
