package pl.barometr.legislative.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reading RPL's own words for where a draft is.
 *
 * The register writes them as a numbered checklist entry — `3. Konsultacje publiczne` —
 * and the number belongs to that draft's list rather than to the stage: a ministry that
 * skips one renumbers the rest, so a mapping that read the number would report a
 * different stage for the same words on the next draft.
 */
class RclStageVocabularyTest {

    @Test
    fun `the checklist number in front of a name is not part of it`() {
        assertEquals(
            LegislativeStage.PUBLIC_CONSULTATION,
            RclStageVocabulary.stageOf("3. Konsultacje publiczne"),
        )
        assertEquals(
            LegislativeStage.PUBLIC_CONSULTATION,
            RclStageVocabulary.stageOf("Konsultacje publiczne"),
            "the same stage, numbered or not",
        )
    }

    @Test
    fun `the stages this model names are read from the register's words`() {
        assertEquals(LegislativeStage.INTER_MINISTERIAL_AGREEMENT, RclStageVocabulary.stageOf("2. Uzgodnienia"))
        assertEquals(LegislativeStage.OPINION, RclStageVocabulary.stageOf("4. Opiniowanie"))
        assertEquals(
            LegislativeStage.STANDING_COMMITTEE,
            RclStageVocabulary.stageOf("Komitet Stały Rady Ministrów"),
        )
        assertEquals(LegislativeStage.COUNCIL_OF_MINISTERS, RclStageVocabulary.stageOf("Rada Ministrów"))
    }

    /**
     * RPL lays its tables out with non-breaking spaces, which no `trim` removes and
     * which `\s` does not match. Left in, "Rada Ministrów" would be a name this model
     * has never heard of.
     */
    @Test
    fun `the spaces RPL lays its tables out with are not part of a name`() {
        assertEquals(
            LegislativeStage.COUNCIL_OF_MINISTERS,
            RclStageVocabulary.stageOf("8.\u00A0Rada\u00A0Ministrów"),
        )
    }

    /**
     * A `ł` written as `l` and a stroke is a different string to one written as a single
     * character, and no reader would ever see the difference.
     */
    @Test
    fun `a name decomposed by the page still matches`() {
        val decomposed = "Komitet Stały Rady Ministro\u0301w"

        assertEquals(LegislativeStage.STANDING_COMMITTEE, RclStageVocabulary.stageOf(decomposed))
    }

    /**
     * RPL's checklist runs through steps this model has no name for. Answering null is
     * what puts the register's own words on the record instead of a stage it did not
     * reach — a legal-drafting commission is not the Council of Ministers.
     */
    @Test
    fun `a stage this model has no name for is left for the label to explain`() {
        assertNull(RclStageVocabulary.stageOf("7. Komisja Prawnicza"))
        assertNull(RclStageVocabulary.stageOf("Notyfikacja"))
    }
}
