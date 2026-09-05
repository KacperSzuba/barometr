package pl.barometr.taxonomy.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.LegislativeKind
import pl.barometr.taxonomy.api.ClassifiedSubject
import java.util.UUID

/**
 * Puts an act or a draft through the classifier and records what came back.
 *
 * The seam between reading a law and deciding about it: the classifier knows about
 * titles and industries and nothing about acts, and [IndustryClassifications] knows
 * about verdicts and thresholds and nothing about where they came from. This is the
 * only place that knows both, which is why the acceptance rule is not restated here —
 * a confidence compared twice is a queue that fills differently depending on who wrote
 * to it.
 *
 * **A subject already read by this lexicon is left alone.** Not an optimisation: the
 * events that bring work here are redelivered and restated — an act is republished
 * whenever the register touches it — and re-recording the same verdicts would reset
 * `decided_at` on rows a reviewer sorts by, moving somebody's queue under them for no
 * new information. A new lexicon version is what makes a subject worth reading again,
 * and it does so for the whole archive at once.
 */
@Service
class LegislationTagging(
    private val catalogue: LegislativeCatalog,
    private val classifier: LexicalIndustryClassifier,
    private val classifications: IndustryClassifications,
    private val verdicts: IndustryVerdictRepository,
    private val properties: ClassificationProperties,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun tagAct(id: ActId): Boolean {
        val act = catalogue.actById(id) ?: return missing("act")

        return tag(ClassifiedSubject(LegislativeKind.ACT, id.value), act.title)
    }

    @Transactional
    fun tagDraft(id: DraftId): Boolean {
        val draft = catalogue.draftById(id) ?: return missing("draft")

        return tag(ClassifiedSubject(LegislativeKind.DRAFT, id.value), draft.title)
    }

    /**
     * The same reading, for a caller that has the subject in front of it already.
     *
     * The walk over the archive pages through titles and would otherwise fetch each one
     * a second time by id to reach the very row it just read.
     */
    @Transactional
    fun tagSubject(kind: String, subjectId: UUID, title: String): Boolean =
        tag(ClassifiedSubject(kind, subjectId), title)

    /**
     * True when the subject was read, which is what a backlog walk counts — including
     * when the reading found nothing, because a title about income tax has been
     * classified as concerning no industry this lexicon knows, and that is an answer.
     */
    private fun tag(subject: ClassifiedSubject, title: String): Boolean {
        if (verdicts.hasVerdictFrom(subject, classifier.version)) {
            meters.counter("taxonomy.classification", "outcome", "already-read").increment()

            return false
        }

        val found = classifier.industriesIn(title)
            .filter { it.confidence >= properties.floorConfidence }

        found.forEach { match ->
            classifications.recordClassification(
                subject = subject,
                code = match.code,
                confidence = match.confidence,
                modelVersion = classifier.version,
                // Why this code and not another. The queue is unworkable without it:
                // "is act 8f3c… about construction" is not a question a subject id and
                // a number let anybody answer.
                matchedOn = match.reasons.joinToString(" · "),
            )
        }

        meters.counter("taxonomy.classification", "outcome", if (found.isEmpty()) "nothing" else "classified")
            .increment()
        if (found.isNotEmpty()) {
            log.debug("{} reads as {}", subject, found.joinToString { "${it.code} (${it.reasons})" })
        }

        return true
    }

    /**
     * A subject the catalogue does not hold. Counted rather than thrown: the event that
     * brought it here may have arrived before the transaction that recorded it
     * committed, and the backlog walk will reach it again.
     */
    private fun missing(kind: String): Boolean {
        meters.counter("taxonomy.classification", "outcome", "$kind-not-found").increment()

        return false
    }
}
