package pl.barometr.search.internal

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch._types.FieldValue
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery
import co.elastic.clients.elasticsearch._types.query_dsl.Query
import co.elastic.clients.elasticsearch._types.query_dsl.TermsQuery
import co.elastic.clients.elasticsearch.core.SearchRequest
import co.elastic.clients.elasticsearch.core.search.HighlightField
import co.elastic.clients.util.NamedValue
import co.elastic.clients.elasticsearch.core.SearchResponse
import org.springframework.stereotype.Service

/**
 * Searching what has been derived, in Polish.
 *
 * Two things a title match alone would miss, both of them how people actually look for
 * a law. The identifiers field is matched as well as the title, so pasting `UD383` or
 * a print number out of an e-mail finds the draft. And the title is boosted well above
 * it, because a search for words is a search for a subject and a coincidental match on
 * an identifier should never outrank one.
 *
 * Filters are filters, not queries: they narrow without touching the score, so ranking
 * stays a statement about the text and nothing else.
 */
@Service
class LegislativeSearch(private val client: ElasticsearchClient) {

    fun search(query: SearchQuery): SearchResults {
        val response = client.search(requestFor(query), Map::class.java)

        return SearchResults(
            total = response.hits().total()?.value() ?: 0,
            hits = response.hits().hits().map(::toHit),
            facets = facetsOf(response),
        )
    }

    private fun requestFor(query: SearchQuery): SearchRequest {
        val matching = BoolQuery.Builder()
            .apply { query.text?.takeIf { it.isNotBlank() }?.let { must(textQuery(it)) } }
            .filter(filtersOf(query))
            .build()
            ._toQuery()

        return SearchRequest.Builder()
            .index(LegislativeIndex.ALIAS)
            .query(matching)
            .from(query.from.coerceAtLeast(0))
            .size(query.size.coerceIn(1, SearchQuery.MAX_SIZE))
            .highlight { highlight ->
                highlight.fields(listOf(NamedValue.of("title", HighlightField.Builder().build())))
            }
            .aggregations(FACETS.associateWith { field ->
                co.elastic.clients.elasticsearch._types.aggregations.Aggregation.Builder()
                    .terms { terms -> terms.field(field).size(FACET_VALUES) }
                    .build()
            })
            .build()
    }

    /**
     * The title carries the meaning, so it outweighs an identifier by a wide margin
     * rather than a nudge: a draft whose number happens to contain the digits somebody
     * typed must not sit above the act they were describing.
     */
    private fun textQuery(text: String): Query = BoolQuery.Builder()
        .should(MatchQuery.of { it.field("title").query(text).boost(TITLE_BOOST) }._toQuery())
        .should(MatchQuery.of { it.field("identifiers").query(text) }._toQuery())
        .minimumShouldMatch("1")
        .build()
        ._toQuery()

    private fun filtersOf(query: SearchQuery): List<Query> = listOfNotNull(
        termsFilter("kind", query.kinds),
        termsFilter("stage", query.stages),
        termsFilter("initiator", query.initiators),
        termsFilter("actType", query.actTypes),
    )

    private fun termsFilter(field: String, values: Set<String>): Query? =
        values.takeIf { it.isNotEmpty() }?.let { chosen ->
            TermsQuery.of { terms ->
                terms.field(field).terms { it.value(chosen.map(FieldValue::of)) }
            }._toQuery()
        }

    private fun toHit(hit: co.elastic.clients.elasticsearch.core.search.Hit<Map<*, *>>): SearchHit {
        val source = hit.source().orEmpty()

        return SearchHit(
            id = hit.id().orEmpty(),
            kind = source["kind"]?.toString().orEmpty(),
            title = source["title"]?.toString().orEmpty(),
            highlightedTitle = hit.highlight()["title"]?.firstOrNull(),
            eli = source["eli"]?.toString(),
            stage = source["stage"]?.toString(),
            outcome = source["outcome"]?.toString(),
            score = hit.score() ?: 0.0,
        )
    }

    private fun facetsOf(response: SearchResponse<Map<*, *>>): Map<String, Map<String, Long>> =
        response.aggregations().mapValues { (_, aggregate) ->
            aggregate.sterms().buckets().array().associate { it.key().stringValue() to it.docCount() }
        }

    private companion object {
        val FACETS = listOf("kind", "stage", "initiator", "actType")
        const val FACET_VALUES = 20
        const val TITLE_BOOST = 8.0f
    }
}
