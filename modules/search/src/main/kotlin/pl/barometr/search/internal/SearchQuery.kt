package pl.barometr.search.internal

/**
 * What somebody is looking for.
 *
 * Every filter is a set rather than a value, because a facet is chosen by clicking
 * several boxes and an empty set means "no opinion" — which reads better at the call
 * site than a null and cannot be confused with "match nothing".
 */
data class SearchQuery(
    /** Free text, matched against titles and against the numbers a draft is quoted by. */
    val text: String?,
    val kinds: Set<String> = emptySet(),
    val stages: Set<String> = emptySet(),
    val initiators: Set<String> = emptySet(),
    val actTypes: Set<String> = emptySet(),
    val from: Int = 0,
    val size: Int = DEFAULT_SIZE,
) {
    companion object {
        const val DEFAULT_SIZE = 20
        const val MAX_SIZE = 100
    }
}
