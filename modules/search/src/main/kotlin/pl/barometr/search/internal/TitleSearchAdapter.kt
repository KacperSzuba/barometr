package pl.barometr.search.internal

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.query_dsl.Operator
import org.springframework.stereotype.Component
import pl.barometr.search.api.TitleMatch
import pl.barometr.search.api.TitleSearch

/**
 * Titles carrying every word of a phrase.
 *
 * Deliberately not the query the search box runs. Browsing wants the best answers to a
 * vague phrase, so that query is an `or` over title and identifiers and lets ranking
 * sort it out; a subscription wants precision, because "any of these words" is how a
 * profile watching *prawo budowlane* starts reporting every act carrying the word
 * *prawo*. Same analyser, same stemming, stricter operator.
 *
 * That strictness is also what keeps this in step with matching a single document
 * against a phrase: both mean "every word of it appears", so a person previewing a
 * keyword sees what they will later be told about.
 */
@Component
class TitleSearchAdapter(private val client: ElasticsearchClient) : TitleSearch {

    override fun titlesMatching(phrase: String, limit: Int): List<TitleMatch> =
        client.search(
            { search ->
                search.index(LegislativeIndex.ALIAS)
                    .size(limit)
                    .query { query ->
                        query.match {
                            it.field(TITLE).query(phrase).operator(Operator.And)
                        }
                    }
            },
            Map::class.java,
        ).hits().hits().map { hit ->
            val source = hit.source().orEmpty()
            TitleMatch(
                kind = source["kind"]?.toString().orEmpty(),
                id = IndexedEntry.idIn(hit.id().orEmpty()),
                title = source["title"]?.toString().orEmpty(),
                eli = source["eli"]?.toString(),
            )
        }

    private companion object {
        const val TITLE = "title"
    }
}
