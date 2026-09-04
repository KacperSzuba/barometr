package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.shared.WorkingDays

/**
 * One document's text, turned into the row a consultation is dated from — or into
 * nothing, with the reason counted.
 *
 * It exists because two callers ask the same question of the same text. A letter
 * arriving as it is derived reaches [ConsultationDeadlineRecorder]; one that was
 * derived before anything was listening is found later by
 * [ConsultationBacklogSweep]. If each did its own arithmetic, the archive would
 * eventually hold deadlines that disagree about a day depending on which path read
 * them, and neither would be wrong on its own terms.
 */
@Component
class ConsultationTerms(
    private val letters: ConsultationLetterReader,
    private val meters: MeterRegistry,
) {

    /**
     * What this text states about when comments are due, or null when it states
     * nothing usable.
     *
     * Null is the ordinary answer. Most of what is filed under a consultation stage is
     * the draft, its justification and an impact assessment, none of which asks anybody
     * for anything.
     */
    fun termStatedIn(text: String, statedIn: DocumentId, statedBy: DocumentVersionId): ConsultationFact? {
        val letter = letters.readLetter(text) ?: return null
        val stated = letter.closingDay() ?: return undated("no-day-to-count-from")

        // `art. 57 § 4 k.p.a.`, applied here because this is where the ministry's
        // arithmetic meets a calendar. The reader deliberately does not know about days
        // off, and the row deliberately stores where the term really ends.
        val closesOn = WorkingDays.endOfTerm(stated)

        if (letter.writtenOn != null && closesOn.isBefore(letter.writtenOn)) {
            // A closing date before the letter that states it is a typo, a date lifted
            // out of a transitional provision, or a reading gone wrong. None of the
            // three is a deadline.
            return undated("closes-before-the-letter")
        }

        return ConsultationFact(
            opensOn = letter.writtenOn,
            closesOn = closesOn,
            daysAllowed = (letter.term as? ConsultationTerm.Period)?.days,
            submissionAddress = letter.submissionAddress,
            quote = letter.quote,
            charStart = letter.charStart,
            charEnd = letter.charEnd,
            statedIn = statedIn,
            statedBy = statedBy,
        )
    }

    /**
     * A document that set out to state a term and could not be read as stating one.
     *
     * Counted rather than logged per document: these fail in shapes, and the size of
     * each pile is what decides whether the reader is worth another pattern.
     */
    private fun undated(reason: String): ConsultationFact? {
        meters.counter("legislative.consultation.undated", "reason", reason).increment()

        return null
    }
}
