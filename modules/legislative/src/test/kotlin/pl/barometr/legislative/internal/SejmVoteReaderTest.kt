package pl.barometr.legislative.internal

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An archived voting read back into the facts this context keeps, against records taken
 * from the Sejm's own voting list.
 */
class SejmVoteReaderTest {

    private val reader = SejmVoteReader(JsonMapper.builder().addModule(kotlinModule()).build())

    private val onABill = requireNotNull(reader.read(fixture("voting-1-3.json")))

    @Test
    fun `a vote carries where it was taken, what it decided and what was counted`() {
        assertEquals(10, onABill.term)
        assertEquals(1, onABill.sitting)
        assertEquals(3, onABill.votingNumber)
        assertEquals(1, onABill.sittingDay)
        assertEquals(Instant.parse("2023-11-13T19:41:22Z"), onABill.takenAt)
        assertEquals("ELECTRONIC", onABill.method)
        assertEquals("ABSOLUTE_MAJORITY", onABill.majority)
        assertEquals(227, onABill.majorityVotes)
        assertEquals(272, onABill.yes)
        assertEquals(27, onABill.no)
        assertEquals(153, onABill.abstained)
        assertEquals(8, onABill.notParticipating)
        assertEquals(452, onABill.totalVoted)
    }

    /** The prints the agenda item names are the whole of the link to a bill. */
    @Test
    fun `the prints of the item voted on are read from its title`() {
        assertEquals(
            listOf(3, 4, 5, 6, 7, 8).map { "term10/print/$it" },
            onABill.citedPrints,
        )
    }

    /**
     * `topic` is the vote and `description` the item it sits under; the narrower of the
     * two is what a reader is shown.
     */
    @Test
    fun `the subject is the register's word for this vote rather than for the item`() {
        assertEquals(
            "Głosowanie nad kandydaturą Posła Krzysztofa Bosaka na stanowisko Wicemarszałka Sejmu",
            onABill.subject,
        )
    }

    /**
     * Most of the voting record decides nothing legislative — a Marshal, a motion, the
     * order of the day. Those are votes, recorded like any other, and they cite nothing.
     */
    @Test
    fun `a vote on a person names no print`() {
        val election = requireNotNull(reader.read(fixture("voting-1-1.json")))

        assertEquals("ON_LIST", election.method)
        assertTrue(election.citedPrints.isEmpty(), "a vote on a candidate cites no bill")
    }

    /** A payload that is not a voting must not become one with everything defaulted. */
    @Test
    fun `something that is not a voting is not read as one`() {
        assertNull(reader.read("""{"title":"Ustawa o czymś","term":10}""".toByteArray()))
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/sejm/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
}
