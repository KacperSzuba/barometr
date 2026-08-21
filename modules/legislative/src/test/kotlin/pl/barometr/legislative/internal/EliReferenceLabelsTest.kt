package pl.barometr.legislative.internal

import org.junit.jupiter.api.Test
import pl.barometr.shared.Eli
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which way round each of the register's labels points.
 *
 * The one error in this table that would be invisible from the outside: reversing a
 * direction produces a graph that reads perfectly and says the opposite of the truth
 * — that the act being amended did the amending.
 */
class EliReferenceLabelsTest {

    private val act = Eli("DU/2026/1074")
    private val other = Eli("DU/2020/770")

    @Test
    fun `acts this one changed point away from it`() {
        assertEquals(
            ActReferenceEdge(act, other, ActRelation.AMENDS),
            EliReferenceLabels.edgeOf("Akty zmienione", act, other),
        )
    }

    @Test
    fun `acts that changed this one point at it`() {
        assertEquals(
            ActReferenceEdge(other, act, ActRelation.AMENDS),
            EliReferenceLabels.edgeOf("Akty zmieniające", act, other),
        )
    }

    @Test
    fun `a repeal is recorded from the act that does the repealing`() {
        assertEquals(
            ActReferenceEdge(act, other, ActRelation.REPEALS),
            EliReferenceLabels.edgeOf("Akty uznane za uchylone", act, other),
        )
        assertEquals(
            ActReferenceEdge(other, act, ActRelation.REPEALS),
            EliReferenceLabels.edgeOf("Akty uchylające", act, other),
        )
    }

    @Test
    fun `a uniform text consolidates the act it restates`() {
        assertEquals(
            ActReferenceEdge(act, other, ActRelation.CONSOLIDATES),
            EliReferenceLabels.edgeOf("Tekst jednolity dla aktu", act, other),
        )
        assertEquals(
            ActReferenceEdge(other, act, ActRelation.CONSOLIDATES),
            EliReferenceLabels.edgeOf("Inf. o tekście jednolitym", act, other),
        )
    }

    @Test
    fun `a regulation implements the act it was issued under`() {
        assertEquals(
            ActReferenceEdge(act, other, ActRelation.IMPLEMENTS),
            EliReferenceLabels.edgeOf("Podstawa prawna", act, other),
        )
        assertEquals(
            ActReferenceEdge(other, act, ActRelation.IMPLEMENTS),
            EliReferenceLabels.edgeOf("Akty wykonawcze", act, other),
        )
    }

    /**
     * Not an oversight. "Uchylenia wynikające z" could mean this act's repeal follows
     * from another, or that its own repeals do, and a confidently wrong repeal is
     * exactly the error this product cannot afford.
     */
    @Test
    fun `a label whose direction is ambiguous produces no edge`() {
        assertNull(EliReferenceLabels.edgeOf("Uchylenia wynikające z", act, other))
        assertNull(EliReferenceLabels.edgeOf("Odesłania", act, other))
        assertNull(EliReferenceLabels.edgeOf("Coś zupełnie nowego", act, other))
    }
}
