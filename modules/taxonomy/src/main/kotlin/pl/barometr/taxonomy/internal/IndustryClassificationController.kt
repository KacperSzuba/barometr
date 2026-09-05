package pl.barometr.taxonomy.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.shared.PkdCode
import pl.barometr.taxonomy.api.ClassifiedSubject
import java.util.UUID

/**
 * Recording which industries a law concerns, and settling what a classifier was unsure
 * about.
 *
 * Operator only, all of it. A verdict here decides who is told about a bill: somebody
 * who could write one could put a competitor's industry on an act and change what
 * lands in their inbox, and registration is open.
 *
 * The write route is shaped for the classifier that will use it — a batch of verdicts
 * about one subject, each with what the model thought and what it read — and a person
 * filling one in by hand is the same route with `manual` and no model.
 */
@RestController
@RequestMapping("/api/v1/taxonomy")
@PreAuthorize("hasRole('OPERATOR')")
class IndustryClassificationController(
    private val classifications: IndustryClassifications,
    private val queue: ClassificationReviewQueue,
) {

    @PutMapping("/subjects/{kind}/{id}/industries")
    fun classify(
        @PathVariable kind: String,
        @PathVariable id: UUID,
        @Valid @RequestBody request: ClassificationRequest,
    ): List<VerdictResponse> {
        val subject = subjectOf(kind, id)

        return request.industries.map { industry ->
            val code = PkdCode.parseOrNull(industry.pkd) ?: throw InvalidIndustryException(industry.pkd)

            describe(
                when (industry.modelVersion) {
                    null -> classifications.recordJudgement(subject, code)
                    else -> classifications.recordClassification(
                        subject = subject,
                        code = code,
                        confidence = industry.confidence,
                        modelVersion = industry.modelVersion,
                        matchedOn = industry.matchedOn,
                        citedVersion = industry.documentVersionId?.let(::DocumentVersionId),
                        charStart = industry.charStart,
                        charEnd = industry.charEnd,
                    )
                },
            )
        }
    }

    /**
     * What a classifier was not sure enough about to route on, oldest first — each with
     * the law's title and the words that caught it, which is the whole of what deciding
     * one takes.
     */
    @GetMapping("/review")
    fun review(): List<ReviewItemResponse> = queue.awaitingReview().map { pending ->
        ReviewItemResponse(title = pending.title, verdict = describe(pending.verdict))
    }

    @PostMapping("/review/decision")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun settle(@Valid @RequestBody decision: ReviewDecision) {
        val code = PkdCode.parseOrNull(decision.pkd) ?: throw InvalidIndustryException(decision.pkd)
        val subject = subjectOf(decision.subjectKind, decision.subjectId)

        if (!classifications.reviewVerdict(subject, code, decision.accept)) {
            throw UnknownVerdictException("$subject / ${code.value}")
        }
    }

    /** A kind outside the vocabulary is a caller's mistake, not an impossible state. */
    private fun subjectOf(kind: String, id: UUID): ClassifiedSubject =
        runCatching { ClassifiedSubject(kind, id) }.getOrElse { throw InvalidIndustryException("subject kind '$kind'") }

    private fun describe(verdict: IndustryVerdict) = VerdictResponse(
        subjectKind = verdict.subject.kind,
        subjectId = verdict.subject.id,
        pkd = verdict.code.value,
        status = verdict.status.wireName,
        confidence = verdict.confidence,
        method = verdict.method.wireName,
        modelVersion = verdict.modelVersion,
        matchedOn = verdict.matchedOn,
        documentVersionId = verdict.citedVersion?.value,
        charStart = verdict.charStart,
        charEnd = verdict.charEnd,
        decidedAt = verdict.decidedAt.toString(),
        reviewedAt = verdict.reviewedAt?.toString(),
    )

    data class ClassificationRequest(
        @field:Size(min = 1, max = 50)
        val industries: List<IndustryRequest>,
    )

    data class IndustryRequest(
        @field:NotBlank
        val pkd: String,
        /** A person's judgement is certain; a model says how sure it was. */
        val confidence: Double = 1.0,
        /** Null means a person decided, which the database refuses to leave pending. */
        val modelVersion: String? = null,
        /** What the classifier matched on. A person's judgement matched nothing. */
        val matchedOn: String? = null,
        val documentVersionId: UUID? = null,
        val charStart: Int? = null,
        val charEnd: Int? = null,
    )

    data class ReviewDecision(
        @field:NotBlank
        val subjectKind: String,
        val subjectId: UUID,
        @field:NotBlank
        val pkd: String,
        val accept: Boolean,
    )

    data class VerdictResponse(
        val subjectKind: String,
        val subjectId: UUID,
        val pkd: String,
        val status: String,
        val confidence: Double,
        val method: String,
        val modelVersion: String?,
        /** The phrase a classifier matched, and the reason a reviewer can act in seconds. */
        val matchedOn: String?,
        val documentVersionId: UUID?,
        val charStart: Int?,
        val charEnd: Int?,
        val decidedAt: String,
        val reviewedAt: String?,
    )

    /**
     * One row of the queue: the verdict, and what the law is called.
     *
     * The title sits beside the verdict rather than inside it, because it is not part
     * of what anybody decided — it is legislative's description of the subject, fetched
     * to make the decision possible.
     */
    data class ReviewItemResponse(
        val title: String?,
        val verdict: VerdictResponse,
    )
}
