package pl.barometr.connectors.rcl.api

import java.time.LocalDate

/** A draft's page, read into the parts worth acting on. */
data class RclProjectCard(
    val projectId: String,
    val title: String,
    /** Keyed by the label RPL prints, with the trailing colon removed. */
    val metadata: Map<String, String>,
    /** Deep link to the ministry's programme of work, when the card carries one. */
    val programmeOfWorkUrl: String?,
    /**
     * The day RPL created the draft — the day it entered the government's process,
     * and the only date on the card that means a beginning rather than a last touch.
     */
    val createdOn: LocalDate?,
    val stages: List<RclStage>,
) {
    val applicant: String? get() = metadata[APPLICANT]
    val status: String? get() = metadata[STATUS]
    val registerNumber: String? get() = metadata[REGISTER_NUMBER]
    val term: String? get() = metadata[TERM]

    /**
     * The term as a number. RPL prints it in roman numerals — `X` — which is how a
     * Pole writes a parliamentary term and not how anything else in this system
     * counts them.
     */
    val termNumber: Int? get() = ROMAN_TERMS[term?.trim()?.uppercase()]

    /** Departments and keywords arrive comma-joined in a single cell. */
    val departments: List<String> get() = splitList(metadata[DEPARTMENTS])
    val keywords: List<String> get() = splitList(metadata[KEYWORDS])

    /** The stages worth fetching: the ones RPL links because they hold something. */
    val visitableStages: List<RclStage> get() = stages.filter { it.isVisitable }

    private fun splitList(value: String?): List<String> =
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val APPLICANT = "Wnioskodawca"
        const val CREATED_AT = "Data utworzenia"
        const val DEPARTMENTS = "Działy"
        const val KEYWORDS = "Hasła"
        const val STATUS = "Status projektu"
        const val PROGRAMME_OF_WORK = "Wykaz prac legislacyjnych"
        const val REGISTER_NUMBER = "Numer z wykazu"
        const val TERM = "Kadencja"
        const val TERM_PERIOD = "Okres kadencji"

        /** Terms I to XX; the Sejm has held ten and this outlasts the decade. */
        private val ROMAN_TERMS: Map<String, Int> = listOf(
            "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X",
            "XI", "XII", "XIII", "XIV", "XV", "XVI", "XVII", "XVIII", "XIX", "XX",
        ).withIndex().associate { (index, numeral) -> numeral to index + 1 }
    }
}
