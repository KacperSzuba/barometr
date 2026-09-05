package pl.barometr.profiles.internal

import pl.barometr.shared.PkdCode
import pl.barometr.taxonomy.api.ClassifiedSubject
import pl.barometr.taxonomy.api.IndustryClassification

/**
 * What a law is about, stated by the test rather than classified.
 *
 * Which industries an act concerns is taxonomy's answer and is settled where it lives.
 * What is under test here is what profiles do with that answer, so it is an input —
 * including the coverage rule, which is asked of [PkdCode] exactly as production asks
 * it, rather than reimplemented with string comparisons a test could get wrong on its
 * own terms.
 */
class FakeIndustries : IndustryClassification {
    private val tagged = mutableMapOf<ClassifiedSubject, MutableList<PkdCode>>()

    fun classifies(subject: ClassifiedSubject, vararg codes: String) {
        tagged.getOrPut(subject) { mutableListOf() }.addAll(codes.map(::PkdCode))
    }

    override fun industriesOf(subject: ClassifiedSubject): List<PkdCode> = tagged[subject].orEmpty()

    override fun classifiedUnder(code: PkdCode, limit: Int): List<ClassifiedSubject> =
        tagged.entries
            .filter { (_, codes) -> codes.any(code::covers) }
            .map { it.key }
            .take(limit)
}
