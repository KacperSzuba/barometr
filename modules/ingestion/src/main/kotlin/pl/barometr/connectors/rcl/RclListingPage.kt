package pl.barometr.connectors.rcl

/**
 * One page of an index, and how much of the collection lies beyond it.
 *
 * [totalCount] comes from the site's own tally rather than from counting rows,
 * which is what lets the walk know how far it has to go before it starts.
 */
data class RclListingPage(
    val totalCount: Int,
    val entries: List<RclListingEntry>,
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** How many pages this collection spans at [pageSize] rows each. */
    fun pageCount(pageSize: Int): Int =
        if (totalCount <= 0) 0 else (totalCount + pageSize - 1) / pageSize
}
