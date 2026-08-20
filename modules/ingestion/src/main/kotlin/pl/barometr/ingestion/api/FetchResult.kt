package pl.barometr.ingestion.api

/**
 * What a connector reports about a pass, beyond what the sink already knows.
 *
 * Deliberately carries no document counts: the sink is handed every payload and is
 * the only thing that can tell a new one from content the archive already held, so
 * a connector counting alongside it produced a second set of numbers that could
 * disagree — and did, since the runner read one set on success and the other on
 * failure.
 */
data class FetchResult(
    /** Null when there is nothing more to resume from. */
    val nextCursor: Cursor?,
    /** Backfill only: this partition has been read to the end. */
    val exhausted: Boolean = false,
    /**
     * The source itself said nothing changed, so no collection was fetched.
     *
     * Distinct from "fetched and found nothing", and the distinction matters: a
     * healthy quarter-hour poll of an idle source legitimately stores zero
     * documents, and without this flag the volume-anomaly check would report an
     * outage every fifteen minutes.
     */
    val sourceUnchanged: Boolean = false,
) {
    companion object {
        val NOTHING = FetchResult(nextCursor = null)
    }
}
