package pl.barometr.taxonomy.internal

import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.shared.PkdCode
import pl.barometr.taxonomy.api.ClassifiedSubject
import java.time.Instant

/**
 * One recorded answer to "is this law about that industry".
 *
 * [confidence] and [method] travel together because they mean different things: a
 * person recording a verdict is certain by definition, and a model reporting 0.95 is
 * not the same claim. Keeping both is what lets the threshold be moved later without
 * rewriting what anybody said.
 */
data class IndustryVerdict(
    val subject: ClassifiedSubject,
    val code: PkdCode,
    val status: VerdictStatus,
    val confidence: Double,
    val method: VerdictMethod,
    val modelVersion: String?,
    val citedVersion: DocumentVersionId?,
    val charStart: Int?,
    val charEnd: Int?,
    val decidedAt: Instant,
    val reviewedAt: Instant?,
) {
    init {
        require(confidence in 0.0..1.0) { "Confidence is a fraction, got $confidence" }
        require((method == VerdictMethod.MODEL) == (modelVersion != null)) {
            "A model verdict names its model and a person's does not"
        }
        require((citedVersion != null) == (charStart != null)) { "A citation is a version and a range" }
    }
}
