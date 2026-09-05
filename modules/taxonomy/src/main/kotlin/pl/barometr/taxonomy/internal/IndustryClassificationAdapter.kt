package pl.barometr.taxonomy.internal

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.shared.PkdCode
import pl.barometr.taxonomy.api.ClassifiedSubject
import pl.barometr.taxonomy.api.IndustryClassification

/**
 * The context's read port. Only accepted verdicts leave: what a classifier was unsure
 * about is a queue for somebody to look at, not a fact to route alerts on.
 */
@Component
@Transactional(readOnly = true)
class IndustryClassificationAdapter(
    private val verdicts: IndustryVerdictRepository,
) : IndustryClassification {

    override fun industriesOf(subject: ClassifiedSubject): List<PkdCode> = verdicts.acceptedFor(subject)

    override fun classifiedUnder(code: PkdCode, limit: Int): List<ClassifiedSubject> =
        verdicts.acceptedUnder(code, limit)
}
