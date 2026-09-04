package pl.barometr.legislative.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.barometr.connectors.rcl.api.RclProjectCard
import pl.barometr.legislative.api.DraftId

/**
 * Opens the row a consultation's deadline will eventually be written into.
 *
 * It runs off the card rather than off a letter because the card is what says a
 * consultation *exists*. The letter that dates it arrives days later, filed among a
 * dozen other documents under the same stage, and without a row already waiting there
 * is nothing to tell it apart from the impact assessment beside it.
 *
 * So the row is opened empty, and an empty row is a real answer: this draft is out for
 * comment and how long there is to reply has not been read yet. What must never happen
 * is the other thing — a deadline filled in from a rule of thumb because a ministry's
 * letter was hard to parse.
 */
@Service
class ConsultationOpening(private val consultations: ConsultationRepository) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun openConsultationsOnCard(draftId: DraftId, card: RclProjectCard) {
        card.stages
            .filter { ConsultationStages.isPublicConsultation(it.name) }
            .forEach { stage ->
                val consultation = consultations.openConsultation(draftId, stage.catalogId)

                log.debug("Consultation {} open on catalog {} of draft {}", consultation, stage.catalogId, draftId)
            }
    }
}
