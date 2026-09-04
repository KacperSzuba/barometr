package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.barometr.connectors.rcl.api.RclFiledDocument
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.corpus.api.ArchivedVersion
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import java.time.Clock
import java.time.Duration

/**
 * Goes back to the archive for the consultations an arriving letter never dated.
 *
 * [ConsultationDeadlineRecorder] reads a letter at the moment its text is derived, and
 * that moment happens once. Two kinds of consultation miss it, and between them they
 * are most of the ones this system holds.
 *
 * A letter derived *before* the card that opened its consultation has nothing to
 * attach to, and these listeners run concurrently on virtual threads. And every letter
 * that was already in the archive when this feature was written was derived before
 * there was anything listening at all — no event will be raised for those again,
 * because the archive is content-addressed and a filed PDF that nobody edits never
 * produces a second version.
 *
 * So this asks the question the other way round: for a consultation with no date, what
 * is filed under it, and does any of it say when comments are due. Same reader, same
 * arithmetic, same first-one-wins rule — [ConsultationTerms] holds all three so the two
 * paths cannot drift into disagreeing about a day.
 *
 * Bounded on both sides: a batch per run, and a consultation looked at is left alone
 * for a while afterwards. A consultation whose documents genuinely state no term — a
 * ministry can file a draft for comment without saying anywhere how long there is to
 * reply — is a permanent member of this queue, and re-reading its dozen files every
 * half hour would be most of what this ever did.
 */
@Component
class ConsultationBacklogSweep(
    private val consultations: ConsultationRepository,
    private val documents: DocumentCatalog,
    private val blobs: BlobStore,
    private val pages: RclPageReader,
    private val terms: ConsultationTerms,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.legislative.consultation-sweep-interval:PT30M}", initialDelay = 120_000)
    @SchedulerLock(name = "legislative-consultation-sweep")
    fun dateConsultationsTheArchiveCanAnswer() {
        val waiting = consultations.undatedConsultations(BATCH, sweptSince = clock.instant().minus(RESWEEP))
        if (waiting.isEmpty()) return

        val dated = waiting.count { consultation ->
            // Marked whatever the answer, and marked even if the reading throws nothing
            // up: an unmarked row is read again in half an hour, for ever.
            dateFromArchive(consultation).also { consultations.markSwept(consultation.id) }
        }

        meters.counter("legislative.consultation.dated", "found", "in-sweep").increment(dated.toDouble())
        log.info("Swept {} undated consultations, dated {}", waiting.size, dated)
    }

    /**
     * Everything filed anywhere under the consultation's stage, in the order RPL lists
     * it, until one of them states a term.
     *
     * The stage's own catalog page is the way in because it renders its whole subtree:
     * one archived page names every file beneath it, including the folder each sits in,
     * which is exactly the list this would otherwise have no way to build.
     */
    private fun dateFromArchive(consultation: UndatedConsultation): Boolean {
        val page = catalogPageOf(consultation) ?: return false

        return page.documents.asSequence()
            .mapNotNull { filed -> termStatedIn(consultation, filed) }
            .any { fact -> consultations.recordTerm(consultation.id, fact) }
    }

    private fun catalogPageOf(consultation: UndatedConsultation) =
        documents
            .latestVersionAt(
                RclCatalogAddress.catalogPageAt(consultation.sourceAddress, consultation.sourceCatalog),
            )
            ?.let { archived -> blobs.read(BlobBucket.RAW, archived.contentHash)?.use { it.readBytes() } }
            ?.let(pages::readCatalog)

    private fun termStatedIn(consultation: UndatedConsultation, filed: RclFiledDocument): ConsultationFact? {
        val archived = documents.latestVersionAt(
            RclCatalogAddress.filedDocumentAt(consultation.sourceAddress, filed.catalogId, filed.documentId),
        ) ?: return null

        return textOf(archived)?.let { text -> terms.termStatedIn(text, archived.documentId, archived.versionId) }
    }

    /**
     * Null for a version with no text, which is the ordinary state of a scan with no
     * text layer — and for one whose extraction has not run yet, which the next sweep
     * will find differently.
     */
    private fun textOf(archived: ArchivedVersion): String? =
        archived.textHash
            ?.let { hash -> blobs.read(BlobBucket.DERIVED, hash)?.use { it.readBytes() } }
            ?.toString(Charsets.UTF_8)

    private companion object {
        /**
         * A batch rather than "until empty". Each consultation costs a catalog page and
         * a dozen documents read out of storage, and a run that worked through a
         * backfill of twenty thousand of them would hold the lock for hours.
         */
        const val BATCH = 50

        /**
         * How long a consultation is left alone after it has been looked at.
         *
         * A day, because the thing that would change the answer is a ministry filing
         * the letter it forgot, and that is a thing that happens on the scale of days.
         */
        val RESWEEP: Duration = Duration.ofDays(1)
    }
}
