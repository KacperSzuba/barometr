package pl.barometr.profiles.internal

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
     * The digits are a hierarchy, not a string: `6` is not a level, and `620` would
     * be a different division if the classification had one. Comparing text without
     * the separator is how `6` would come to cover `62`.
     */
    @Test
    fun `a code does not cover one that merely starts with the same digits`() {
        assertFalse(PkdCode("62.0").covers(PkdCode("62.01")))
        assertFalse(PkdCode("62.01").covers(PkdCode("62.02.Z")))
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
