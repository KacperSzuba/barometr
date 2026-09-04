package pl.barometr.legislative.internal

import pl.barometr.legislative.api.ConsultationId

/**
 * A consultation this system knows is open and cannot say when it closes, with the two
 * strings needed to go looking in the archive.
 *
 * [sourceAddress] is where the draft's card lives — `projekt/ustawa/12409051` — and
 * [sourceCatalog] the folder the consultation was opened on. Every document that could
 * answer the question is addressed by appending segments to the first.
 */
data class UndatedConsultation(
    val id: ConsultationId,
    val sourceCatalog: String,
    val sourceAddress: String,
)
