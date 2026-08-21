package pl.barometr.legislative.internal

import org.junit.jupiter.api.Test
import pl.barometr.shared.Eli
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An archived legislative process read back into the facts this context keeps,
 * against records taken from the Sejm's own register.
 */
class SejmProcessReaderTest {

    private val reader = SejmProcessReader(JsonMapper.builder().addModule(kotlinModule()).build())

    private val enacted = requireNotNull(reader.read(fixture("process-31.json")))

    @Test
    fun `a finished bill carries its outcome, its act and the print it arrived as`() {
        assertEquals("31", enacted.printNumber)
        assertEquals(10, enacted.term)
        assertEquals(DraftInitiator.CITIZENS, enacted.initiator)
        assertTrue(enacted.isDraft)
        assertEquals(Eli("DU/2023/2730"), enacted.eli)
        assertEquals(LocalDate.parse("2023-11-29"), enacted.closedOn)
        assertEquals(DraftOutcome.ENACTED, enacted.outcome)
    }

    @Test
    fun `the register's stage types are read as this model's stages`() {
        val stages = enacted.stages.map { it.stage }

        assertEquals(
            listOf(
                LegislativeStage.SUBMITTED_TO_SEJM,
                LegislativeStage.REFERRED_TO_FIRST_READING,
                LegislativeStage.FIRST_READING,
                LegislativeStage.COMMITTEE_WORK,
                LegislativeStage.SECOND_READING,
                LegislativeStage.COMMITTEE_WORK,
                LegislativeStage.THIRD_READING,
                LegislativeStage.SENATE_POSITION,
                LegislativeStage.SENT_TO_PRESIDENT,
                LegislativeStage.PRESIDENT_SIGNED,
                // "Uchwalono": the verdict on the passage, and no stage at all.
                null,
            ),
            stages,
        )
    }

    /**
     * All three readings arrive typed `SejmReading`; only the name says which. Reading
     * them as one stage would flatten the middle of the path, which is where most of
     * what a user waits for happens.
     */
    @Test
    fun `the three readings are told apart by name`() {
        val readings = enacted.stages.filter { it.sourceLabel.contains("czytanie") }

        assertEquals(
            listOf(
                LegislativeStage.FIRST_READING,
                LegislativeStage.SECOND_READING,
                LegislativeStage.THIRD_READING,
            ),
            readings.map { it.stage },
        )
    }

    @Test
    fun `a bill still moving has no outcome`() {
        val open = requireNotNull(reader.read(fixture("process-27.json")))

        assertNull(open.closedOn)
        assertNull(open.outcome, "a bill that has not been decided has not been rejected either")
        assertNull(open.eli)
        assertEquals(3, open.stages.size)
    }

    /**
     * The register carries motions, lists of candidates and government information
     * alongside bills. They are archived like everything else and are not drafts.
     */
    @Test
    fun `a process that is not a bill or a resolution is not a draft`() {
        val candidates = requireNotNull(reader.read(fixture("process-3.json")))

        assertFalse(candidates.isDraft)
        assertTrue(candidates.title.startsWith("Kandydat"))
    }

    /**
     * Three stages the register types that this model had no name for until the archive
     * produced them: the Sejm voting on the Senate's amendments, a presidential veto, and
     * a draft its author took back. The counter on unmapped labels is what found them,
     * which is what the counter is for.
     */
    @Test
    fun `the stages the archive argued for are read`() {
        assertEquals(
            LegislativeStage.SENATE_POSITION_CONSIDERED,
            SejmStageVocabulary.stageOf("SenatePositionConsideration", "Rozpatrywanie stanowiska Senatu"),
        )
        assertEquals(
            LegislativeStage.PRESIDENT_VETO,
            SejmStageVocabulary.stageOf("Veto", "Wniosek Prezydenta (weto)"),
        )
    }

    /**
     * Documents arriving *at* a draft rather than places the draft has got to. Giving them
     * a stage would say it moved when it did not, so they stay unnamed — on purpose, and
     * recorded with the register's own words either way.
     */
    @Test
    fun `an opinion filed on a draft is not a stage the draft reached`() {
        assertNull(SejmStageVocabulary.stageOf("Opinion", "Opinia organizacji samorządowej"))
        // The register's own spelling of `Government`, which is what has to be matched.
        assertNull(SejmStageVocabulary.stageOf("GovermentPosition", "Wpłynęło stanowisko rządu"))
    }

    /**
     * The register says `passed: false` for a draft voted down and for one taken back
     * alike; only the closing word tells them apart, and reporting a withdrawal as a
     * rejection is a claim about the Sejm that the Sejm never made.
     */
    @Test
    fun `a withdrawn draft is not a rejected one`() {
        val payload = """{"number":"99","title":"Poselski projekt ustawy o czymś","term":10,"documentTypeEnum":"BILL","passed":false,"closureDate":"2023-12-05","stages":[{"stageType":"End","stageName":"Wycofano","date":"2023-12-05"}]}"""

        val withdrawn = requireNotNull(reader.read(payload.toByteArray()))

        assertEquals(DraftOutcome.WITHDRAWN, withdrawn.outcome)
    }
    @Test
    fun `a payload that is not a process is refused rather than half-read`() {
        assertNull(reader.read("""{"title":"Something else"}""".toByteArray()))
        assertNull(reader.read("""{"number":"31","title":"A bill"}""".toByteArray()))
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/sejm/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
}
