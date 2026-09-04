package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentTextExtracted
import pl.barometr.legislative.api.ConsultationId
import pl.barometr.shared.WorkingDays
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore

/**
 * Reads the deadline out of a document filed under a consultation, and records it
 * beside the sentence it was read from.
 *
 * The last link of the chain that starts at a ministry uploading a PDF: ingestion
 * archived the bytes, corpus turned them into text, and this asks the one question a
 * reader will act on — until when may I write in. It runs on the extracted text rather
 * than on the file, so the offsets it stores index exactly the characters a citation
 * is rendered from.
 *
 * **Every file filed under the stage passes through, and the first whose words set a
 * term wins.** That is the schema's rule and it is a deliberate one: the covering
 * letter is not marked as such anywhere in RPL, so the alternative to reading them all
 * is reading none. The narrowing is done by
 * [ConsultationLetterReader], which takes a date only from a sentence that asks for
 * comments — a table of comments quoting its own letter could still win the race, and
 * `stated_document` is what makes that visible when it does.
 *
 * A letter arriving before the card that opens its consultation is dropped rather than
 * held: these listeners run concurrently, and while the card is fetched first and re-read
 * every six hours, a letter derived in that window is one this system will only date
 * when a later version of it is filed.
 */
@Service
class ConsultationDeadlineRecorder(
    private val documents: DocumentCatalog,
    private val blobs: BlobStore,
    private val letters: ConsultationLetterReader,
    private val consultations: ConsultationRepository,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun recordDeadlineStatedInDocument(extracted: DocumentTextExtracted) {
        val document = documents.documentById(extracted.documentId) ?: return
        val filedIn = RclCatalogAddress.ofFiledDocument(document.externalId) ?: return
        val consultation = consultations.consultationInCatalog(filedIn.catalogId) ?: return

        val text = blobs.read(BlobBucket.DERIVED, extracted.textHash)?.use { it.readBytes() }
        if (text == null) {
            log.warn("No extracted text for {} at {}", document.externalId, extracted.textHash)
            return
        }

        val letter = letters.readLetter(text.toString(Charsets.UTF_8)) ?: return
        record(consultation, letter, extracted)
    }

    private fun record(
        consultation: ConsultationId,
        letter: ConsultationLetter,
        extracted: DocumentTextExtracted,
    ) {
        val stated = letter.closingDay()
            ?: return leaveUndated("no-day-to-count-from", consultation)

        // `art. 57 § 4 k.p.a.`, applied here because this is where the ministry's
        // arithmetic meets a calendar. The reader deliberately does not know about
        // days off, and the row deliberately stores where the term really ends.
        val closesOn = WorkingDays.endOfTerm(stated)

        if (letter.writtenOn != null && closesOn.isBefore(letter.writtenOn)) {
            // A closing date before the letter that states it is a typo, a date lifted
            // out of a transitional provision, or a reading gone wrong. None of the
            // three is a deadline.
            return leaveUndated("closes-before-the-letter", consultation)
        }

        val recorded = consultations.recordTerm(
            consultation,
            ConsultationFact(
                opensOn = letter.writtenOn,
                closesOn = closesOn,
                daysAllowed = (letter.term as? ConsultationTerm.Period)?.days,
                submissionAddress = letter.submissionAddress,
                quote = letter.quote,
                charStart = letter.charStart,
                charEnd = letter.charEnd,
                statedIn = extracted.documentId,
                statedBy = extracted.versionId,
            ),
        )

        if (recorded) {
            meters.counter("legislative.consultation.dated").increment()
            log.info("Consultation {} closes on {}, stated by {}", consultation, closesOn, extracted.versionId)
        } else {
            log.debug("Consultation {} was already dated by another document", consultation)
        }
    }

    /**
     * A consultation this system knows is open and still cannot date.
     *
     * Counted rather than logged per document: the letters that fail this way fail in
     * shapes, and the size of each pile is what decides whether the reader is worth
     * another pattern.
     */
    private fun leaveUndated(reason: String, consultation: ConsultationId) {
        meters.counter("legislative.consultation.undated", "reason", reason).increment()
        log.debug("Consultation {} left undated: {}", consultation, reason)
    }
}
