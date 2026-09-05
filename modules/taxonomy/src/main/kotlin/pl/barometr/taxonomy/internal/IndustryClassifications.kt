package pl.barometr.taxonomy.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.shared.PkdCode
import pl.barometr.taxonomy.api.ClassifiedSubject
import java.time.Clock

/**
 * Recording what somebody — or something — decided a law is about.
 *
 * The one place the threshold is applied, and the reason it is a service rather than a
 * line in a controller: the same rule has to hold for a person filling in a code by
 * hand, for the classifier that will POST a batch of them, and for whatever comes
 * after. A confidence compared in two places is a queue that fills differently
 * depending on who wrote to it.
 *
 * **A person's verdict is accepted, a model's has to clear the bar.** Somebody typing a
 * code has done the reviewing; a model reporting 0.4 has asked a question. The database
 * holds the first half of that rule as a `CHECK`, so it cannot be got round from here.
 */
@Service
class IndustryClassifications(
    private val verdicts: IndustryVerdictRepository,
    private val properties: ClassificationProperties,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** A person's judgement: accepted as recorded, and reviewed by the act of recording it. */
    @Transactional
    fun recordJudgement(subject: ClassifiedSubject, code: PkdCode): IndustryVerdict =
        record(
            IndustryVerdict(
                subject = subject,
                code = code,
                status = VerdictStatus.ACCEPTED,
                confidence = 1.0,
                method = VerdictMethod.MANUAL,
                modelVersion = null,
                matchedOn = null,
                citedVersion = null,
                charStart = null,
                charEnd = null,
                decidedAt = clock.instant(),
                reviewedAt = clock.instant(),
            ),
        )

    /**
     * A classifier's verdict, accepted or queued according to how sure it was.
     *
     * [citedVersion] and the range are what makes the verdict checkable — "this act is
     * about construction because of these words" — and are optional because a model
     * reading a title has nothing narrower to point at than the title.
     */
    @Transactional
    fun recordClassification(
        subject: ClassifiedSubject,
        code: PkdCode,
        confidence: Double,
        modelVersion: String,
        /** What the classifier matched on, which is the whole of what a reviewer is shown. */
        matchedOn: String? = null,
        citedVersion: DocumentVersionId? = null,
        charStart: Int? = null,
        charEnd: Int? = null,
    ): IndustryVerdict =
        record(
            IndustryVerdict(
                subject = subject,
                code = code,
                status = if (confidence >= properties.acceptanceThreshold) {
                    VerdictStatus.ACCEPTED
                } else {
                    VerdictStatus.PENDING
                },
                confidence = confidence,
                method = VerdictMethod.MODEL,
                modelVersion = modelVersion,
                matchedOn = matchedOn,
                citedVersion = citedVersion,
                charStart = charStart,
                charEnd = charEnd,
                decidedAt = clock.instant(),
                reviewedAt = null,
            ),
        )

    /** Settles one queued verdict. Rejections are kept, because a wrong tag is training data. */
    @Transactional
    fun reviewVerdict(subject: ClassifiedSubject, code: PkdCode, accept: Boolean): Boolean {
        val settled = verdicts.settleVerdict(
            subject = subject,
            code = code,
            status = if (accept) VerdictStatus.ACCEPTED else VerdictStatus.REJECTED,
            reviewedAt = clock.instant(),
        )

        if (settled) {
            meters.counter("taxonomy.verdict.reviewed", "outcome", if (accept) "accepted" else "rejected").increment()
        }

        return settled
    }

    fun pendingReview(): List<IndustryVerdict> = verdicts.pendingVerdicts(properties.reviewPageSize)

    private fun record(verdict: IndustryVerdict): IndustryVerdict {
        verdicts.recordVerdict(verdict)
        meters.counter("taxonomy.verdict.recorded", "status", verdict.status.wireName).increment()
        log.debug("{} is {} {} ({})", verdict.subject, verdict.status.wireName, verdict.code, verdict.method.wireName)

        return verdict
    }
}
