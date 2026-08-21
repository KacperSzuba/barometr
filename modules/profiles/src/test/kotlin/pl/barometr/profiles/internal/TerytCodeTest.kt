package pl.barometr.profiles.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TerytCodeTest {

    @Test
    fun `a voivodeship covers the counties and municipalities inside it`() {
        val mazowieckie = TerytCode("14")

        assertTrue(mazowieckie.covers(TerytCode("1465")))
        assertTrue(mazowieckie.covers(TerytCode("1465011")))
        assertFalse(mazowieckie.covers(TerytCode("02")))
    }

    @Test
    fun `a length TERYT does not use is not a place`() {
        assertNull(TerytCode.parseOrNull("146"))
        assertNull(TerytCode.parseOrNull("14650"))
        assertNull(TerytCode.parseOrNull("mazowieckie"))
    }
}
