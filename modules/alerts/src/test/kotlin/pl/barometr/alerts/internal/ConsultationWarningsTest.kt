package pl.barometr.alerts.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which of the three warnings a consultation is due, decided from how many chances to
 * file are left.
 *
 * A pure question with sharp edges, so it is asked here rather than through a run: the
 * two that matter are the day a band opens and the day the whole thing is still too far
 * off to mention.
 */
class ConsultationWarningsTest {

    @Test
    fun `each band opens on the day the count reaches it`() {
        assertEquals(20, ConsultationWarnings.bandFor(20))
        assertEquals(10, ConsultationWarnings.bandFor(10))
        assertEquals(3, ConsultationWarnings.bandFor(3))
    }

    /**
     * The narrowest band that still holds it. Anything else would send the fortnight's
     * warning to somebody with three days left.
     */
    @Test
    fun `a count inside a band is that band's warning, not a wider one`() {
        assertEquals(20, ConsultationWarnings.bandFor(15))
        assertEquals(10, ConsultationWarnings.bandFor(7))
        assertEquals(3, ConsultationWarnings.bandFor(1))
    }

    /** Closing today is the last band, not no band: there is still a morning to file in. */
    @Test
    fun `a consultation closing today is still in the last band`() {
        assertEquals(3, ConsultationWarnings.bandFor(0))
    }

    @Test
    fun `further off than the first warning is nobody's news yet`() {
        assertNull(ConsultationWarnings.bandFor(21))
        assertNull(ConsultationWarnings.bandFor(60))
    }
}
