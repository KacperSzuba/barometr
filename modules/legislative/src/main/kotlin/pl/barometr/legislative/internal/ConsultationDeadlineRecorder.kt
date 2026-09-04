package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentTextExtracted
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
 * is reading none. The narrowing is done by [ConsultationLetterReader], which takes a
 * date only from a sentence that asks for comments — a table of comments quoting its
 * own letter could still win the race, and `stated_document` is what makes that visible
 * when it does.
 *
 * A letter derived before the card that opens its consultation is dropped here rather
 * than held, and so is every letter that was already in the archive when this was
 * written. Neither is lost: [ConsultationBacklogSweep] goes back for them, which is
 * why this path can stay a single cheap listener.
 */
@Service
class ConsultationDeadlineRecorder(
    private val documents: DocumentCatalog,
    private val blobs: BlobStore,
    private val terms: ConsultationTerms,
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

        val fact = terms.termStatedIn(text.toString(Charsets.UTF_8), extracted.documentId, extracted.versionId)
            ?: return

        if (consultations.recordTerm(consultation, fact)) {
            meters.counter("legislative.consultation.dated", "found", "on-arrival").increment()
            log.info("Consultation {} closes on {}, stated by {}", consultation, fact.closesOn, fact.statedBy)
        } else {
            log.debug("Consultation {} was already dated by another document", consultation)
        }
    }
}
