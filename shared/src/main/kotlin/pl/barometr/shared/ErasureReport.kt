package pl.barometr.shared

/**
 * What one context deleted when an account was closed, and what it did not.
 *
 * **[kept] is the important half.** Some things survive a deletion for reasons that are
 * lawful and worth stating out loud rather than discovering later: an append-only audit
 * trail whose whole value is that nobody can edit it, a suppression list that exists to
 * honour somebody's earlier "stop mailing me", aggregate counts with no person in them.
 * A deletion that quietly leaves data behind is the failure this type exists to prevent;
 * one that says what it left and why is a promise that can be checked.
 */
data class ErasureReport(
    val category: String,
    /** Table name to rows removed. */
    val deleted: Map<String, Int>,
    /** Table name to the reason anything there survives. */
    val kept: Map<String, String>,
) {
    val rowsDeleted: Int get() = deleted.values.sum()
}
