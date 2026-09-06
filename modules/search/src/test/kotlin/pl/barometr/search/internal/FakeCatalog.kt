package pl.barometr.search.internal

import pl.barometr.corpus.api.DocumentId
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.FiledUnderDraft
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.LegislativeSignals
import pl.barometr.legislative.api.PublishedAct
import pl.barometr.legislative.api.TrackedDraft
import pl.barometr.shared.Eli
import pl.barometr.shared.Ids
import java.time.LocalDate

/**
 * What legislative would answer, without a database.
 *
 * Shared by the tests that index from it and the ones that search what was indexed,
 * because the two are asserting different halves of one contract and a second copy of
 * this would let them drift.
 */
class FakeCatalog : LegislativeCatalog {

    val acts = listOf(
        PublishedAct(
            id = ActId(Ids.next()),
            eli = Eli("DU/2026/1074"),
            title = "Ustawa z dnia 17 lipca 2026 r. o cenach energii elektrycznej",
            type = "Ustawa",
            publisher = "DU",
            announcedOn = LocalDate.parse("2026-08-10"),
            inForceFrom = LocalDate.parse("2027-02-11"),
        ),
        PublishedAct(
            id = ActId(Ids.next()),
            eli = Eli("MP/2026/12"),
            title = "Uchwała Sejmu w sprawie powołania członka Rady",
            type = "Uchwała",
            publisher = "MP",
            announcedOn = LocalDate.parse("2026-01-20"),
            inForceFrom = null,
        ),
    )

    val drafts = listOf(
        TrackedDraft(
            id = DraftId(Ids.next()),
            title = "Rządowy projekt ustawy o zmianie ustawy o cenach energii",
            initiator = "rzadowy",
            term = 10,
            startedOn = LocalDate.parse("2026-03-01"),
            closedOn = null,
            outcome = null,
            currentStage = "ii_czytanie",
            identifiers = listOf("term10/print/424", "UD383"),
        ),
    )

    private val filings = mutableMapOf<DocumentId, FiledUnderDraft>()

    /** Says that this archived file was filed under [draft], as RPL's catalog page would. */
    fun fileUnder(documentId: DocumentId, draft: TrackedDraft, fileName: String?) {
        filings[documentId] = FiledUnderDraft(documentId, draft.id, draft.title, fileName)
    }

    override fun actById(id: ActId) = acts.firstOrNull { it.id == id }

    override fun actByEli(eli: Eli) = acts.firstOrNull { it.eli == eli }

    override fun draftById(id: DraftId) = drafts.firstOrNull { it.id == id }

    /** Nothing here ranks anything; the signals are somebody else's question. */
    override fun signalsForDraft(id: DraftId): LegislativeSignals? = null

    override fun filedUnderDrafts(documentIds: List<DocumentId>): List<FiledUnderDraft> =
        documentIds.mapNotNull(filings::get)

    override fun actsAfter(after: ActId?, limit: Int) =
        acts.dropWhile { after != null && it.id.value <= after.value }.take(limit)

    override fun draftsAfter(after: DraftId?, limit: Int) =
        drafts.dropWhile { after != null && it.id.value <= after.value }.take(limit)
}
