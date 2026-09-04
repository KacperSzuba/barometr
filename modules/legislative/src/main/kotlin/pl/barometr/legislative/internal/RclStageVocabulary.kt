package pl.barometr.legislative.internal

import java.text.Normalizer
import java.util.Locale

/**
 * What RPL's stage names mean in this model.
 *
 * Names rather than codes, because the register has no codes: a stage change is an
 * entry saying the attribute "nazwa etapu" now reads `3. Konsultacje publiczne`, and
 * those words are the whole of what the source states. The number in front is the
 * position on that draft's own checklist rather than a stage identity — RPL renumbers
 * when a ministry skips one — so it is stripped instead of read.
 *
 * **Only the names this model already has a stage for are mapped.** RPL's checklist
 * also runs through a legal-drafting commission, a European affairs committee and a
 * notification step, and inventing a mapping for those would say a draft reached the
 * Council of Ministers when it reached a lawyer's desk. They arrive as
 * [LegislativeStage.UNKNOWN] with the register's own words beside them, which is
 * visible where a wrong mapping would not be.
 */
object RclStageVocabulary {

    /** Null when the register named a stage this model has no name for. */
    fun stageOf(stageName: String): LegislativeStage? = when (normalise(stageName)) {
        "wykaz prac legislacyjnych" -> LegislativeStage.PROGRAMME_OF_WORK
        "uzgodnienia" -> LegislativeStage.INTER_MINISTERIAL_AGREEMENT
        "konsultacje publiczne" -> LegislativeStage.PUBLIC_CONSULTATION
        "opiniowanie" -> LegislativeStage.OPINION
        "komitet stały rady ministrów" -> LegislativeStage.STANDING_COMMITTEE
        "rada ministrów" -> LegislativeStage.COUNCIL_OF_MINISTERS
        else -> null
    }

    /**
     * The name as it can be compared with the spellings above: without its checklist
     * number, lower case, with runs of whitespace collapsed — and composed, so that a
     * `ł` arriving as `l` plus a stroke is the same string as the one written here.
     *
     * Composed rather than folded to ASCII, unlike [ActTitles]: that fold exists so a
     * search can be forgiving, and there is nothing to be forgiving about between a
     * fixed list and a register that writes the same six names every time.
     */
    private fun normalise(stageName: String): String =
        Normalizer.normalize(stageName.replace(NUMBER_PREFIX, ""), Normalizer.Form.NFC)
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")
            .trim()

    /** `3. ` or `3) ` in front of the name, which is where the draft's checklist counts. */
    private val NUMBER_PREFIX = Regex("""^\s*\d+\s*[.)]\s*""")

    /** `\s` alone leaves the non-breaking spaces RPL lays its tables out with. */
    private val WHITESPACE = Regex("""[\s\u00A0]+""")
}
