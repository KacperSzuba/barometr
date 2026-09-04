package pl.barometr.alerts.internal

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.barometr.legislative.api.ConsultationCalendar
import pl.barometr.shared.WorkingDays
import java.time.Clock
import java.time.LocalDate

/**
 * Puts a consultation into the buffer while there is still time to write into it.
 *
 * The first alert this system raises that is not about something having happened. A
 * draft moving is news and keeps; a consultation closing on Friday is a window, and a
 * reader told about it on Saturday has been told nothing at all. So the trigger is not
 * an event arriving — no event ever will — but a date getting near, which only a run
 * looking at the calendar can notice.
 *
 * **Counted in working days.** "Three days" means three chances to file, and over a
 * long weekend three calendar days are one — which is precisely when a term is most
 * likely to be missed and least likely to be recoverable. How many warnings there are
 * and how far out is [ConsultationWarnings]; all this decides is that the consultation
 * is near enough to be worth judging at all.
 *
 * It buffers rather than notifies. Everything about who hears, whether they heard this
 * morning, and what it is worth beside the rest of their week belongs to the run that
 * already decides those things; a second path to a person's inbox would be a second
 * place for "why was I told twice" to have an answer.
 *
 * Idempotent by construction, which is what lets it run four times a day rather than
 * once at a particular hour: a consultation stays inside the warning bands for its last
 * month, and the notification's event key — the consultation, the day it closes and
 * which warning this is — is what turns those repeated sightings into three alerts.
 */
@Component
class ConsultationDeadlineWatch(
    private val calendar: ConsultationCalendar,
    private val pending: PendingItemRepository,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.alerts.consultation-watch-interval:PT6H}", initialDelay = 60_000)
    @SchedulerLock(name = "alerts-consultation-watch")
    fun bufferConsultationsClosingSoon() {
        val today = LocalDate.now(clock)

        val buffered = calendar.closingBetween(today, today.plusDays(HORIZON_DAYS))
            .filter { ConsultationWarnings.bandFor(WorkingDays.between(today, it.closesOn)) != null }
            .count { pending.append(ConsultationNotice.KIND, it.id.value.toString()) }

        // Only the ones newly buffered are worth a line. The rest are the same
        // consultations this run saw six hours ago, still waiting to be judged.
        if (buffered > 0) log.info("Buffered {} consultations closing within the warning bands", buffered)
    }

    private companion object {
        /**
         * How far ahead the calendar is asked, in calendar days. Comfortably more than
         * the furthest warning can span: twenty working days is four weeks of them, and
         * a Christmas or an Easter inside that stretches it by a few days more. The
         * working-day count above does the actual narrowing, so asking wide costs
         * nothing but rows.
         */
        const val HORIZON_DAYS = 45L
    }
}
