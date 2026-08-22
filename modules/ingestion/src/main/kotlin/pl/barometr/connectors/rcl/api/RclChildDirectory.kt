package pl.barometr.connectors.rcl.api

import java.time.LocalDate

/**
 * A catalog filed inside another one — the folders a stage is divided into.
 *
 * "Konsultacje publiczne" holds five: the draft itself, the letters sending it out,
 * the positions submitted in response, the applicant's reply to those comments, and a
 * conference. Which of them a document sits in is most of what the document means.
 */
data class RclChildDirectory(
    val catalogId: String,
    val name: String,
    val lastModifiedAt: LocalDate?,
)
