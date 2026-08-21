package pl.barometr.search.internal

import org.springframework.stereotype.Component
import pl.barometr.search.api.TitleMatch
import pl.barometr.search.api.TitleSearch

/**
 * The published port over the same query the search endpoint runs.
 *
 * The same query on purpose: a phrase that a profile says it watches must find what
 * the search box finds, or a person will add a keyword, see the results, and be told
 * about something else entirely.
 */
@Component
class TitleSearchAdapter(private val search: LegislativeSearch) : TitleSearch {

    override fun titlesMatching(phrase: String, limit: Int): List<TitleMatch> =
        search.search(SearchQuery(text = phrase, size = limit)).hits.map {
            TitleMatch(
                kind = it.kind,
                id = IndexedEntry.idIn(it.id),
                title = it.title,
                eli = it.eli,
            )
        }
}
