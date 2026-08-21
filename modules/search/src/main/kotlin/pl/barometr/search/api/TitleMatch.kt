package pl.barometr.search.api

/**
 * One thing a phrase found, named the way its own context names it — `act` or `draft`
 * plus that context's identifier, not the index's.
 */
data class TitleMatch(
    val kind: String,
    val id: String,
    val title: String,
    val eli: String?,
) {
    /**
     * The two kinds a match can be, stated where they cross the boundary.
     *
     * A caller has to compare `kind` against something, and the alternative is every
     * caller spelling `"act"` for itself — one typo away from a filter that silently
     * keeps nothing.
     */
    companion object {
        const val ACT = "act"
        const val DRAFT = "draft"
    }
}
