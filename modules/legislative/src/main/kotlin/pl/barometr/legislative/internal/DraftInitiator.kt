package pl.barometr.legislative.internal

/**
 * Who put the draft forward, matching the `CHECK` on `draft.initiator`.
 *
 * Read from the title, because that is where the Sejm states it: every draft's title
 * opens with the word for its origin. `documentTypeEnum` says only whether it is a
 * bill or a resolution, which is a different question.
 */
enum class DraftInitiator(val wireName: String) {
    GOVERNMENT("rzadowy"),
    DEPUTIES("poselski"),
    CITIZENS("obywatelski"),
    SENATE("senacki"),
    PRESIDENT("prezydencki"),
    COMMITTEE("komisyjny"),
    SEJM_PRESIDIUM("prezydium_sejmu"),

    /**
     * A title this table does not recognise. Counted where it is used, so a new form
     * of words argues for its own entry instead of quietly becoming a seventh of
     * something else.
     */
    UNKNOWN("nieznany"),
    ;

    companion object {
        fun of(title: String): DraftInitiator = when {
            title.startsWith("Rządowy") -> GOVERNMENT
            title.startsWith("Poselski") -> DEPUTIES
            title.startsWith("Obywatelski") -> CITIZENS
            title.startsWith("Senacki") -> SENATE
            title.startsWith("Komisyjny") -> COMMITTEE
            title.startsWith("Przedstawiony przez Prezydenta") -> PRESIDENT
            title.startsWith("Przedstawiony przez Prezydium Sejmu") -> SEJM_PRESIDIUM
            title.startsWith("Przedstawiony przez Senat") -> SENATE
            else -> UNKNOWN
        }
    }
}
