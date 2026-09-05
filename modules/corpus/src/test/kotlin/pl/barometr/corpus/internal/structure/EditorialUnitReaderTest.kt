package pl.barometr.corpus.internal.structure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The parser everything else rests on, held to the shapes ministries actually file.
 *
 * The assertion repeated everywhere is that a unit's span, cut out of the input,
 * is the unit — because that span is what a citation renders, and an off-by-one here
 * is a quote that says something the document does not.
 */
class EditorialUnitReaderTest {

    private val reader = EditorialUnitReader()

    @Test
    fun `articles, paragraphs, points and letters are read with the path they are cited by`() {
        val text = """
            Art. 5. Przedsiębiorca prowadzi ewidencję.
            1. Ewidencja obejmuje:
            1) nazwę towaru;
            2) datę wprowadzenia, w tym:
            a) godzinę,
            b) miejsce.
            2. Ewidencję przechowuje się przez 5 lat.
            Art. 6. Ustawa wchodzi w życie po upływie 14 dni.
        """.trimIndent()

        val paths = reader.unitsIn(text).map { it.path.value }

        assertEquals(
            listOf(
                "art-5",
                "art-5/ust-1",
                "art-5/ust-1/pkt-1",
                "art-5/ust-1/pkt-2",
                "art-5/ust-1/pkt-2/lit-a",
                "art-5/ust-1/pkt-2/lit-b",
                "art-5/ust-2",
                "art-6",
            ),
            paths,
        )
    }

    @Test
    fun `a unit spans its own words and not its children`() {
        val text = """
            Art. 5. Przedsiębiorca prowadzi ewidencję.
            1. Ewidencja obejmuje nazwę towaru.
        """.trimIndent()

        val units = reader.unitsIn(text).associateBy { it.path.value }

        assertEquals("Art. 5. Przedsiębiorca prowadzi ewidencję.", units.getValue("art-5").textIn(text))
        assertEquals("1. Ewidencja obejmuje nazwę towaru.", units.getValue("art-5/ust-1").textIn(text))
    }

    @Test
    fun `everything before the first numbered unit is the preamble`() {
        val text = """
            USTAWA
            z dnia 3 marca 2026 r.
            o zmianie ustawy o cenach energii

            Art. 1. W ustawie wprowadza się zmiany.
        """.trimIndent()

        val units = reader.unitsIn(text)

        assertEquals(UnitKind.PREAMBLE, units.first().kind)
        assertTrue(units.first().textIn(text).startsWith("USTAWA"))
        assertTrue(units.first().textIn(text).endsWith("cenach energii"))
    }

    @Test
    fun `a wrapped line beginning with a number does not open a paragraph`() {
        // What a PDF does to a long sentence: the line break falls before "30." and the
        // number is a term, not a designator.
        val text = """
            Art. 5. Wniosek składa się w terminie
            30. dnia od dnia doręczenia decyzji.
            1. Wniosek zawiera uzasadnienie.
        """.trimIndent()

        val units = reader.unitsIn(text)

        assertEquals(listOf("art-5", "art-5/ust-1"), units.map { it.path.value })
        assertTrue(
            units.first().textIn(text).contains("30. dnia"),
            "the wrapped line belongs to the article it continues",
        )
    }

    @Test
    fun `a regulation numbered with the section sign is read like a statute`() {
        val text = """
            § 1. Rozporządzenie określa sposób prowadzenia rejestru.
            § 2. 1. Wpis obejmuje numer.
            2. Wpisu dokonuje się niezwłocznie.
        """.trimIndent()

        assertEquals(
            listOf("par-1", "par-2", "par-2/ust-1", "par-2/ust-2"),
            reader.unitsIn(text).map { it.path.value },
        )
    }

    @Test
    fun `divisions carry the articles written beneath them`() {
        val text = """
            DZIAŁ II
            Przepisy szczegółowe

            Rozdział 3
            Ewidencja

            Art. 12a. Ewidencję prowadzi się w postaci elektronicznej.
        """.trimIndent()

        assertEquals(
            listOf("dz-ii", "dz-ii/rozdz-3", "dz-ii/rozdz-3/art-12a"),
            reader.unitsIn(text).map { it.path.value },
        )
    }

    @Test
    fun `tirets are numbered where they sit`() {
        val text = """
            Art. 5. Ewidencja obejmuje:
            1) dane o towarze, w tym:
            – nazwę,
            – masę netto;
            2) dane o przewoźniku.
        """.trimIndent()

        val paths = reader.unitsIn(text).map { it.path.value }

        assertTrue(paths.containsAll(listOf("art-5/pkt-1/tir-1", "art-5/pkt-1/tir-2")), paths.toString())
        assertTrue(paths.contains("art-5/pkt-2"), paths.toString())
    }

    @Test
    fun `an article inserted by an amendment keeps its letter`() {
        val text = """
            Art. 12. Pierwszy przepis.
            Art. 12a. Przepis dodany nowelizacją.
            Art. 13. Kolejny przepis.
        """.trimIndent()

        assertEquals(
            listOf("art-12", "art-12a", "art-13"),
            reader.unitsIn(text).map { it.path.value },
        )
    }

    @Test
    fun `point numbering starts again inside the next article`() {
        val text = """
            Art. 5. Ewidencja obejmuje:
            1) nazwę;
            2) masę.
            Art. 6. Rejestr obejmuje:
            1) numer;
            2) datę.
        """.trimIndent()

        val paths = reader.unitsIn(text).map { it.path.value }

        assertEquals(listOf("art-5", "art-5/pkt-1", "art-5/pkt-2", "art-6", "art-6/pkt-1", "art-6/pkt-2"), paths)
    }

    @Test
    fun `the first paragraph written on the article's own line is its own unit`() {
        val text = """
            Art. 5. 1. Przedsiębiorca prowadzi ewidencję.
            2. Ewidencję przechowuje się przez 5 lat.
        """.trimIndent()

        val units = reader.unitsIn(text).associateBy { it.path.value }

        assertEquals(listOf("art-5", "art-5/ust-1", "art-5/ust-2"), units.keys.toList())
        assertEquals("Art. 5.", units.getValue("art-5").textIn(text))
        assertEquals("1. Przedsiębiorca prowadzi ewidencję.", units.getValue("art-5/ust-1").textIn(text))
    }

    @Test
    fun `a document with no units at all is one preamble`() {
        val text = "Uzasadnienie\n\nProjekt realizuje wyrok Trybunału Konstytucyjnego."

        val units = reader.unitsIn(text)

        assertEquals(1, units.size)
        assertEquals(UnitKind.PREAMBLE, units.single().kind)
        assertEquals(text, units.single().textIn(text))
    }

    @Test
    fun `every span cut out of the text is the unit it claims`() {
        val text = """
            Art. 1. Pierwszy.

            Art. 2. Drugi.
        """.trimIndent()

        reader.unitsIn(text).forEach { unit ->
            val span = unit.textIn(text)
            assertFalse(span.first().isWhitespace(), "a unit does not start on whitespace: '$span'")
            assertFalse(span.last().isWhitespace(), "a unit does not end on whitespace: '$span'")
        }
    }
}
