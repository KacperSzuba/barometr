package pl.barometr.legislative.internal

import pl.barometr.shared.Eli

/**
 * What ISAP's reference labels mean, and which way round.
 *
 * The API groups references under Polish labels that state the relation *and* its
 * direction: "Akty zmienione" is what this act changed, "Akty zmieniające" is what
 * changed it. Reading either as the other reverses the change graph, which is the
 * one error in this table nobody would notice from the outside — both directions
 * look plausible on a page.
 *
 * Only labels whose meaning is unambiguous are mapped. "Uchylenia wynikające z" and
 * "Odesłania" are left out deliberately: the first could mean this act's repeal
 * follows from another or that its repeals do, and guessing the direction of a repeal
 * is exactly the kind of confident error that costs a product like this its
 * credibility. Unmapped labels are counted, so their volume is visible and a decision
 * about them is made on evidence.
 */
object EliReferenceLabels {

    private enum class Direction {
        /** The act being read is the source of the edge. */
        FROM_ACT,

        /** The act being read is what the edge points at. */
        TO_ACT,
    }

    private val MEANINGS: Map<String, Pair<Direction, ActRelation>> = mapOf(
        "Akty zmienione" to (Direction.FROM_ACT to ActRelation.AMENDS),
        "Akty zmieniające" to (Direction.TO_ACT to ActRelation.AMENDS),
        "Akty uchylone" to (Direction.FROM_ACT to ActRelation.REPEALS),
        "Akty uznane za uchylone" to (Direction.FROM_ACT to ActRelation.REPEALS),
        "Akty uchylające" to (Direction.TO_ACT to ActRelation.REPEALS),
        "Tekst jednolity dla aktu" to (Direction.FROM_ACT to ActRelation.CONSOLIDATES),
        "Inf. o tekście jednolitym" to (Direction.TO_ACT to ActRelation.CONSOLIDATES),
        "Podstawa prawna" to (Direction.FROM_ACT to ActRelation.IMPLEMENTS),
        "Podstawa prawna z art." to (Direction.FROM_ACT to ActRelation.IMPLEMENTS),
        "Akty wykonawcze" to (Direction.TO_ACT to ActRelation.IMPLEMENTS),
    )

    /** Null when the label carries no relation this system is prepared to assert. */
    fun edgeOf(label: String, act: Eli, referenced: Eli): ActReferenceEdge? {
        val (direction, relation) = MEANINGS[label] ?: return null

        return when (direction) {
            Direction.FROM_ACT -> ActReferenceEdge(act, referenced, relation)
            Direction.TO_ACT -> ActReferenceEdge(referenced, act, relation)
        }
    }
}
