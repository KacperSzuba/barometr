package pl.barometr.search.internal

/**
 * What a search found, and what it could be narrowed by.
 *
 * The facets are counted over the result set as filtered, which has a consequence
 * worth knowing before it surprises somebody: choosing `kind=ustawa` leaves the kind
 * facet showing only that kind. Counting each facet against the query minus its own
 * filter is what a faceted browser eventually wants, and it is a `post_filter` away —
 * left undone rather than half-done, so the numbers mean exactly one thing today.
 */
data class SearchResults(
    val total: Long,
    val hits: List<SearchHit>,
    val facets: Map<String, Map<String, Long>>,
)
