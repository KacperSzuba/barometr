package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.barometr.connectors.rcl.api.RclPageReader
import pl.barometr.corpus.api.ArchivedVersion
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore

/**
 * Opens consultations from the cards already in the archive.
 *
 * The other half of [ConsultationBacklogSweep], and the half that has to run first. A
 * card opens a consultation when it is *projected*, and it is projected when a new
 * version of it is archived — so a draft whose card has not changed since this feature
 * was written has no consultation row at all, and the sweep that goes looking for
 * letters has nothing to look for them against. On an archive that has been filling up
 * for months that is most of the drafts in it.
 *
 * **Only consultations are opened here.** The projector also records a draft, its
 * identifiers, its entry into the process and an event announcing it, and re-running
 * all of that over the whole archive would restate twenty thousand drafts nobody has
 * asked about and put every one of them through the alerting engine. The one thing
 * missing is the consultation row, so the one thing done is opening it.
 *
 * The walk restarts from the beginning of the archive on every run and still finishes:
 * a card that has been read is marked on its draft, so a second pass steps over it for
 * the price of one indexed lookup, and each run spends its parsing budget further in
 * than the last.
 *
 * Which leaves the state this spends almost all of its life in — nothing left to read —
 * and the walk has no way of recognising it from the inside: its only ending is the end
 * of the archive, so it would visit every card ever stored, every hour, to discover one
 * lookup at a time that there is nothing to do. So the question is asked once, of the
 * drafts rather than of the archive, before any of it starts.
 */
@Component
class ArchivedCardSweep(
    private val documents: DocumentCatalog,
    private val blobs: BlobStore,
    private val pages: RclPageReader,
    private val drafts: DraftRepository,
    private val consultations: ConsultationOpening,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.legislative.card-sweep-interval:PT1H}", initialDelay = 180_000)
    @SchedulerLock(name = "legislative-card-sweep")
    fun openConsultationsFromArchivedCards() {
        if (!drafts.anyCardStillToRead()) return

        var after: DocumentId? = null
        var read = 0

        while (read < CARDS_PER_RUN) {
            val page = documents.versionsOfKind(PROJECT, after, PAGE)
            if (page.isEmpty()) break

            read += page.count(::openConsultationsOn)
            after = page.last().documentId
        }

        if (read > 0) {
            meters.counter("legislative.consultation.cards_read").increment(read.toDouble())
            log.info("Read {} archived cards for consultation stages", read)
        }
    }

    /**
     * True when the card was actually read, which is what the run's budget counts.
     *
     * The draft is looked up from the address rather than from the card's contents, so
     * a card whose draft has already been through this costs one indexed query and no
     * parsing at all — which is what the whole archive costs once the backlog is done.
     */
    private fun openConsultationsOn(archived: ArchivedVersion): Boolean {
        val projectId = RclCatalogAddress.projectIn(archived.externalId) ?: return false
        val draftId = drafts.draftAwaitingConsultationsFromCard(projectId) ?: return false

        val payload = blobs.read(BlobBucket.RAW, archived.contentHash)?.use { it.readBytes() }
        val card = payload?.let(pages::readProjectCard)

        // Marked whatever the card turned out to say, including when it could not be
        // read at all. A card with no public-consultation stage is an answer, and one
        // this cannot parse is a warning with an address in it — while an unmarked draft
        // is a page this walk fetches and re-parses on every run for ever.
        card?.let { consultations.openConsultationsOnCard(draftId, it, archived.externalId.value) }
            ?: log.warn("No readable card at {} for draft {}", archived.externalId, draftId)
        drafts.markConsultationsReadFromCard(draftId)

        return true
    }

    private companion object {
        val PROJECT = DocumentKind("rcl-project")

        const val PAGE = 200

        /**
         * How many cards are fetched and parsed in one run. A bound rather than "until
         * done": a backfill is twenty-four thousand cards, and a run that read them all
         * would hold the lock for the best part of an hour and delay everything else
         * this deployment schedules.
         */
        const val CARDS_PER_RUN = 500
    }
}
