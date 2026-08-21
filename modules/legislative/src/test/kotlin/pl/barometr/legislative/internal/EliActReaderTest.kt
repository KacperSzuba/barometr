package pl.barometr.legislative.internal

import org.junit.jupiter.api.Test
import pl.barometr.shared.Eli
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An archived act read back into the facts this context keeps, against a payload
 * recorded from the live register.
 */
class EliActReaderTest {

    private val reader = EliActReader(JsonMapper.builder().addModule(kotlinModule()).build())

    private val act = requireNotNull(reader.read(fixture("isap/act-with-prints.json")))

    @Test
    fun `an act is keyed on its ELI and dated by the journal`() {
        assertEquals(Eli("DU/2026/1074"), act.eli)
        assertEquals("Ustawa", act.type)
        assertTrue(act.title.startsWith("Ustawa z dnia 17 lipca 2026 r."))
        // `promulgation`, the day it appeared in Dziennik Ustaw — not the date on the
        // act, which the register sometimes states as a year in the twenty-third
        // century.
        assertEquals(LocalDate.parse("2026-08-10"), act.announcedOn)
        assertEquals(LocalDate.parse("2027-02-11"), act.inForceFrom)
    }

    /**
     * The bridge the whole identity story rests on: the register names the Sejm print
     * the act came from, so a draft reaches its act without anyone comparing titles.
     */
    @Test
    fun `the Sejm print behind an act is read as the address the archive uses`() {
        val print = act.prints.single()

        assertEquals(SejmPrintReference(term = 10, number = "2620"), print)
        assertEquals("term10/print/2620", print.documentAddress)
    }

    @Test
    fun `references are oriented, so what the act changed points away from it`() {
        val amended = act.references.filter { it.relation == ActRelation.AMENDS }
        val repealed = act.references.filter { it.relation == ActRelation.REPEALS }

        assertEquals(6, amended.size)
        assertTrue(amended.all { it.from == act.eli }, "this act is what did the amending")
        assertTrue(amended.any { it.to == Eli("DU/2005/1538") })

        assertEquals(2, repealed.size)
        assertTrue(repealed.all { it.from == act.eli })
        assertTrue(repealed.any { it.to == Eli("DU/2020/770") })
    }

    @Test
    fun `a payload that is not an act is refused rather than half-read`() {
        assertNull(reader.read("""{"title":"Something else"}""".toByteArray()))
        assertNull(reader.read("""{"ELI":"DU/2026/1074"}""".toByteArray()))
    }

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "Missing fixture $name" }
            .use { it.readBytes() }
}
