package pl.barometr.profiles.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.profiles.internal.jooq.tables.references.PROFILE_INTEREST

/**
 * The stems beside each keyword. SQL only.
 */
@Repository
class KeywordStemRepository(private val dsl: DSLContext) {

    /** Keywords nobody has stemmed yet, each once however many profiles chose it. */
    fun unstemmed(): List<String> =
        dsl.selectDistinct(PROFILE_INTEREST.VALUE)
            .from(PROFILE_INTEREST)
            .where(PROFILE_INTEREST.KIND.eq(InterestKind.KEYWORD.wireName))
            .and(PROFILE_INTEREST.STEMS.isNull)
            .fetch(PROFILE_INTEREST.VALUE)
            .filterNotNull()

    /**
     * Writes the stems for every copy of [keyword], in every version that holds it.
     *
     * Every version, not only the live ones: an old version is what an alert cites when
     * somebody asks why they were told something, and one that could not be re-matched
     * would answer that question with silence.
     */
    @Transactional
    fun remember(keyword: String, stems: List<String>) {
        dsl.update(PROFILE_INTEREST)
            .set(PROFILE_INTEREST.STEMS, stems.toTypedArray<String?>())
            .where(PROFILE_INTEREST.KIND.eq(InterestKind.KEYWORD.wireName))
            .and(PROFILE_INTEREST.VALUE.eq(keyword))
            .execute()
    }
}
