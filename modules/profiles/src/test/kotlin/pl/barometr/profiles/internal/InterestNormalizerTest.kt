package pl.barometr.profiles.internal

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InterestNormalizerTest {

    private val normalizer = InterestNormalizer()

    @Test
    fun `each kind is read in its own vocabulary`() {
        val draft = UUID.randomUUID()

        assertEquals("62.01.Z", read(InterestKind.PKD, " 62.01.z "))
        assertEquals("1465", read(InterestKind.REGION, " 1465 "))
        assertEquals("DU/2024/1222", read(InterestKind.ACT, "du/2024/1222"))
        assertEquals(draft.toString(), read(InterestKind.DRAFT, draft.toString().uppercase()))
    }

    /**
     * Two spellings of one interest would be two rows under a key that includes the
     * value, and would look identical in the list the person reads back.
     */
    @Test
    fun `a keyword is flattened to what the index would match anyway`() {
        assertEquals("prawo budowlane", read(InterestKind.KEYWORD, "  Prawo   Budowlane "))
    }

    @Test
    fun `a keyword too short to mean anything is refused`() {
        assertFailsWith<InvalidInterestException> { read(InterestKind.KEYWORD, "od") }
    }

    @Test
    fun `a value its kind cannot read is refused rather than stored`() {
        assertFailsWith<InvalidInterestException> { read(InterestKind.PKD, "J") }
        assertFailsWith<InvalidInterestException> { read(InterestKind.REGION, "Warszawa") }
        assertFailsWith<InvalidInterestException> { read(InterestKind.ACT, "ustawa o VAT") }
        assertFailsWith<InvalidInterestException> { read(InterestKind.DRAFT, "UD383") }
    }

    private fun read(kind: InterestKind, value: String): String =
        normalizer.normalize(Interest(kind, value)).value
}
