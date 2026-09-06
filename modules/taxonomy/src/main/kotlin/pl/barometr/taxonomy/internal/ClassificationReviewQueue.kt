package pl.barometr.taxonomy.internal

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.taxonomy.api.ClassifiedSubject

/**
 * The queue as somebody actually works through it.
 *
 * A pending verdict is a subject id, a code and a number, and none of those is a
 * question anybody can answer: "is act 8f3c… about construction" needs the act's name,
 * and the phrase that caught it needs to be beside it or the reviewer is re-doing the
 * classifier's reading by hand. The first comes from legislative, the second travels on
 * the verdict itself.
 *
 * A title per row rather than one query for all of them, because the catalogue has no
 * batch read and a page is fifty: fifty lookups by primary key against an answer
 * somebody is about to spend a minute on each of.
 */
@Service
class ClassificationReviewQueue(
    private val classifications: IndustryClassifications,
    private val catalogue: LegislativeCatalog,
) {

    @Transactional(readOnly = true)
    fun awaitingReview(): List<PendingClassification> =
        classifications.pendingReview().map { PendingClassification(it, titleOf(it.subject)) }

    private fun titleOf(subject: ClassifiedSubject): String? = when (subject.kind) {
        LegislativeKind.ACT -> catalogue.actById(ActId(subject.id))?.title
        LegislativeKind.DRAFT -> catalogue.draftById(DraftId(subject.id))?.title
        // Unreachable while `ClassifiedSubject` refuses every other kind, and left as an
        // absence rather than an error: a queue that fails to render is worse than one
        // row without a name.
        else -> null
    }
}
