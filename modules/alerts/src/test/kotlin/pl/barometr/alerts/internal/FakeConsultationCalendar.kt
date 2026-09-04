package pl.barometr.alerts.internal

import pl.barometr.legislative.api.ConsultationCalendar
import pl.barometr.legislative.api.ConsultationDeadline
import pl.barometr.legislative.api.ConsultationId
import pl.barometr.legislative.api.DraftId
import java.time.LocalDate

/**
 * What is out for comment, as much of it as an alert needs: whose draft, until when,
 * and the sentence that said so.
 *
 * Only consultations a date was read for are held, which is the port's own rule — a
 * consultation waiting for its letter is invisible to every caller, and a fake that
 * handed one back would let a test pass that production could not.
 */
class FakeConsultationCalendar : ConsultationCalendar {
    private val open = mutableMapOf<ConsultationId, ConsultationDeadline>()

    fun opens(
        id: ConsultationId,
        draft: DraftId,
        title: String,
        closesOn: LocalDate,
        daysAllowed: Int? = 21,
    ) {
        open[id] = ConsultationDeadline(
            id = id,
            draftId = draft,
            draftTitle = title,
            opensOn = closesOn.minusDays(daysAllowed?.toLong() ?: 0),
            closesOn = closesOn,
            daysAllowed = daysAllowed,
            submissionAddress = "konsultacje@ms.gov.pl",
            quote = "proszę o zgłoszenie uwag w terminie 21 dni od dnia otrzymania niniejszego pisma",
        )
    }

    /** A ministry extending a consultation states a new day for the same one. */
    fun extends(id: ConsultationId, to: LocalDate) {
        open[id] = open.getValue(id).copy(closesOn = to)
    }

    override fun closingBetween(from: LocalDate, until: LocalDate): List<ConsultationDeadline> =
        open.values
            .filter { !it.closesOn.isBefore(from) && !it.closesOn.isAfter(until) }
            .sortedBy { it.closesOn }

    override fun consultationById(id: ConsultationId): ConsultationDeadline? = open[id]
}
