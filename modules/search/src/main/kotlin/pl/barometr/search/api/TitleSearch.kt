package pl.barometr.search.api

/**
 * Finds acts and drafts by what they are called.
 *
 * Exists for the caller that has a phrase somebody typed into a profile and needs to
 * know what it would catch. Free-text matching is the index's competence — Polish
 * stemming included — and re-implementing it as a `LIKE` over Postgres is how the same
 * phrase would come to mean two different things depending on which screen asked.
 */
interface TitleSearch {

    /** Best matches first, at most [limit] of them. */
    fun titlesMatching(phrase: String, limit: Int): List<TitleMatch>
}
