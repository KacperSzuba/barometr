package pl.barometr.connectors.rcl

/**
 * How far a draft has got with one stage, as RPL itself renders it.
 *
 * Three states rather than a progression, because RPL does not enforce one. On a
 * real card in the fixtures, "Uzgodnienia" is finished, "Konsultacje publiczne" was
 * never started, and "Opiniowanie" is under way — a stage skipped outright while a
 * later one runs. Any model that treats these as an ordered pipeline will
 * misrepresent that draft, which is why the state recorded here is the site's claim
 * and nothing more.
 */
enum class RclStageState {
    /** The stage exists but holds nothing yet. */
    NOT_STARTED,

    /** Where the draft is now. */
    CURRENT,

    /** Rendered as passed: it holds documents and is not the current stage. */
    DONE,

    /** The markup said something this parser does not recognise. */
    UNKNOWN,
}
