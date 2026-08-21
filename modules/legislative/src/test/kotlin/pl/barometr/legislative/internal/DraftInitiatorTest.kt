package pl.barometr.legislative.internal

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Who put a draft forward, read from the word its title opens with.
 *
 * The titles below are real ones from the Sejm's register of term X, in the
 * proportions they appear: mostly deputies and the government, and a tail that the
 * schema's original vocabulary did not admit at all.
 */
class DraftInitiatorTest {

    @Test
    fun `the common origins are read from the opening word`() {
        assertEquals(DraftInitiator.GOVERNMENT, DraftInitiator.of("Rządowy projekt ustawy o zmianie ustawy"))
        assertEquals(DraftInitiator.DEPUTIES, DraftInitiator.of("Poselski projekt uchwały w sprawie"))
        assertEquals(DraftInitiator.CITIZENS, DraftInitiator.of("Obywatelski projekt ustawy o zmianie"))
        assertEquals(DraftInitiator.COMMITTEE, DraftInitiator.of("Komisyjny projekt ustawy o zmianie"))
        assertEquals(DraftInitiator.SENATE, DraftInitiator.of("Senacki projekt ustawy o zmianie"))
    }

    /**
     * Twenty-two drafts of three hundred in one term open this way, and the Presidium
     * of the Sejm is none of the six origins the schema was written with.
     */
    @Test
    fun `a draft introduced by the Presidium has an origin of its own`() {
        assertEquals(
            DraftInitiator.SEJM_PRESIDIUM,
            DraftInitiator.of("Przedstawiony przez Prezydium Sejmu projekt uchwały"),
        )
        assertEquals(
            DraftInitiator.PRESIDENT,
            DraftInitiator.of("Przedstawiony przez Prezydenta Rzeczypospolitej Polskiej projekt ustawy"),
        )
    }

    @Test
    fun `a title in a form nobody has seen is unknown rather than guessed`() {
        assertEquals(DraftInitiator.UNKNOWN, DraftInitiator.of("Zupełnie nowy rodzaj projektu"))
    }
}
