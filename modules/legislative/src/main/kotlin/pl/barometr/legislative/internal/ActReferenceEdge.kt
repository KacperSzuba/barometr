package pl.barometr.legislative.internal

import pl.barometr.shared.Eli

/**
 * One edge of the change graph, already pointing the right way.
 *
 * Orientation is settled here rather than downstream because the source states it
 * both ways round: an act lists both what it changed and what changed it, and a
 * reader that passed that ambiguity on would leave every consumer deciding which
 * direction a Polish label implies.
 */
data class ActReferenceEdge(
    val from: Eli,
    val to: Eli,
    val relation: ActRelation,
)
