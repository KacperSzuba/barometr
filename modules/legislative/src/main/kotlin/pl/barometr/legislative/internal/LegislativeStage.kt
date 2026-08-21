package pl.barometr.legislative.internal

/**
 * The path a draft takes, as one vocabulary across three sources.
 *
 * Declaration order is the canonical order of the path, and it is load-bearing: the
 * status engine reads "what probably comes next" from it. What the order is *not* is
 * a promise that a draft visits these in sequence — a bill goes back to committee
 * between its second and third readings as a matter of routine, and the schema
 * records that rather than refusing it.
 *
 * Which source fills which stretch is stated here because it is the first question
 * anyone asks of an empty stage: the government's own process is RPL's, the passage
 * through parliament is the Sejm's, and publication is ISAP's.
 */
enum class LegislativeStage(val wireName: String) {

    // ——— Rządowy proces legislacyjny, z RPL ————————————————————————————————
    /**
     * In the government's process, stage unknown — the coarse fact an RPL card can
     * actually support.
     *
     * A card is a checklist: stages with a state each and, on the few that have moved,
     * a last-modified stamp. That stamp is not the day a stage began, so the finer
     * stages below wait for the change registers, which time events to the minute.
     * What the card does state is the day the draft entered the process, and saying
     * that much is far better than leaving a draft with no position in time at all.
     */
    GOVERNMENT_PROCESS("proces_rzadowy"),
    PROGRAMME_OF_WORK("wykaz_prac"),
    INTER_MINISTERIAL_AGREEMENT("uzgodnienia"),
    PUBLIC_CONSULTATION("konsultacje_publiczne"),
    OPINION("opiniowanie"),
    STANDING_COMMITTEE("komitet_staly"),
    COUNCIL_OF_MINISTERS("rada_ministrow"),

    // ——— Sejm, z rejestru procesów legislacyjnych ——————————————————————————
    SUBMITTED_TO_SEJM("wplynal_do_sejmu"),
    REFERRED_TO_FIRST_READING("skierowany_do_i_czytania"),
    FIRST_READING("i_czytanie"),
    COMMITTEE_WORK("praca_w_komisjach"),
    SECOND_READING("ii_czytanie"),
    THIRD_READING("iii_czytanie"),
    SENATE_POSITION("stanowisko_senatu"),
    SENT_TO_PRESIDENT("przekazany_prezydentowi"),
    PRESIDENT_SIGNED("podpisany_przez_prezydenta"),
    PRESIDENT_TO_TRIBUNAL("skierowany_do_trybunalu"),


    /**
     * A stage the source described in words this model has no name for.
     *
     * Recorded rather than dropped, with the source's own label beside it: a history
     * with a gap in it is worse than one with an honest "we did not recognise this",
     * and the gap would be invisible where the label is not.
     */
    UNKNOWN("nieznany"),
    ;

    companion object {
        fun of(wireName: String): LegislativeStage? = entries.firstOrNull { it.wireName == wireName }
    }
}
