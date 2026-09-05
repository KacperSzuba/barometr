package pl.barometr.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The prefix rule the whole industry match rests on. */
class PkdCodeTest {

    @Test
    fun `a division covers everything classified beneath it`() {
        val division = PkdCode("62")

        assertTrue(division.covers(PkdCode("62.01")))
        assertTrue(division.covers(PkdCode("62.01.Z")))
        assertTrue(division.covers(division))
    }

    /**
     * A group holds the classes beneath it, and the printed form hides that: `62.0` is
     * a prefix of `62.01` as text, but `"62.0."` is not, so a `startsWith` on the
     * printed code has a group covering nothing at all. Whoever chose an industry at
     * group level would then be told about none of it, and nothing would fail.
     */
    @Test
    fun `a group covers the classes written beneath it`() {
        assertTrue(PkdCode("62.0").covers(PkdCode("62.01")))
        assertTrue(PkdCode("62.0").covers(PkdCode("62.01.Z")))
    }

    /**
     * The digits are a hierarchy, not a string: a code covers what is *below* it, and
     * a neighbouring class is not below anything.
     */
    @Test
    fun `a code does not cover its neighbours`() {
        assertFalse(PkdCode("62.01").covers(PkdCode("62.02.Z")))
        assertFalse(PkdCode("62.02").covers(PkdCode("62.01")))
        assertFalse(PkdCode("62").covers(PkdCode("63.01")))
    }

    /**
     * Every level a subscriber could have chosen, so that catching an act tagged at
     * subclass level is four equality lookups rather than a pattern match against every
     * interest ever stored.
     */
    @Test
    fun `a code names every level it belongs to`() {
        assertEquals(
            listOf(PkdCode("62"), PkdCode("62.0"), PkdCode("62.01"), PkdCode("62.01.Z")),
            PkdCode("62.01.Z").ancestry(),
        )
        assertEquals(listOf(PkdCode("62")), PkdCode("62").ancestry())
        assertEquals(listOf(PkdCode("41"), PkdCode("41.2")), PkdCode("41.2").ancestry())
    }

    @Test
    fun `everything a code names covers it`() {
        val subclass = PkdCode("41.20.Z")

        subclass.ancestry().forEach { level ->
            assertTrue(level.covers(subclass), "$level should cover $subclass")
        }
    }

    @Test
    fun `a subclass covers nothing but itself`() {
        val subclass = PkdCode("41.20.Z")

        assertTrue(subclass.covers(subclass))
        assertFalse(subclass.covers(PkdCode("41.20")))
    }

    /**
     * A section is a letter standing for a range of divisions, and which divisions it
     * covers is not a fact this system holds. Accepting `J` would mean matching
     * nothing while looking like a choice that works.
     */
    @Test
    fun `a section letter is not a code this system can match`() {
        assertNull(PkdCode.parseOrNull("J"))
        assertNull(PkdCode.parseOrNull("A"))
    }

    @Test
    fun `what a person types is read as written or refused`() {
        assertEquals(PkdCode("62.01.Z"), PkdCode.parseOrNull(" 62.01.z "))
        assertNull(PkdCode.parseOrNull("62.01.ZZ"))
        assertNull(PkdCode.parseOrNull("6"))
        assertNull(PkdCode.parseOrNull("62,01"))
        assertNull(PkdCode.parseOrNull(""))
    }
}
