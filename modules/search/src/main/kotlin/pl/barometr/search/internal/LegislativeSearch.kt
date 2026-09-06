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
import pl.barometr.corpus.api.DocumentId
import pl.barometr.legislative.api.FiledUnderDraft
import pl.barometr.legislative.api.LegislativeCatalog
import java.util.UUID

/**
 * Searching what has been derived, in Polish.
 *
 * Three things a title match alone would miss, all of them how people actually look for
 * a law. The identifiers field is matched as well as the title, so pasting `UD383` or
 * a print number out of an e-mail finds the draft. The text of the files filed under a
 * draft is matched too, so a phrase that appears on page forty of an impact assessment
 * and in nobody's title is findable at all. And the title is boosted well above both,
 * because a search for words is a search for a subject: an act *called* "o cenach
 * energii" must outrank the hundred documents that mention energy prices in passing.
 *
 * Filters are filters, not queries: they narrow without touching the score, so ranking
 * stays a statement about the text and nothing else.
 */
@Service
class LegislativeSearch(
    private val client: ElasticsearchClient,
    private val catalog: LegislativeCatalog,
) {

    fun search(query: SearchQuery): SearchResults {
        val response = client.search(requestFor(query), Map::class.java)

        return SearchResults(
            total = response.hits().total()?.value() ?: 0,
            hits = withTheirDrafts(response.hits().hits().map(::toHit)),
            facets = facetsOf(response),
        )
    }

    /**
     * Which draft each document hit was filed under, asked once for the whole page.
     *
     * Read here rather than held in the index, because the link is made after the file
     * is: a document usually reaches the archive before the card that creates its draft,
     * so an index that recorded the draft at indexing time would record none and would
     * have to be told to come back. One indexed query per search keeps the answer as
     * current as the database, and costs a page of ids.
     */
    private fun withTheirDrafts(hits: List<SearchHit>): List<SearchHit> {
        val documentIds = hits.filter { it.kind == IndexedEntry.DOCUMENT }
            .mapNotNull { documentIdIn(it.id) }
        if (documentIds.isEmpty()) return hits

        val byDocument = catalog.filedUnderDrafts(documentIds).associateBy { it.documentId }

        return hits.map { hit ->
            when (val filedUnder = documentIdIn(hit.id)?.let(byDocument::get)) {
                null -> hit
                else -> hit.copy(filedUnder = filedUnder)
            }
        }
    }

    /** An index id is `document:<uuid>`; anything else here is not a document we wrote. */
    private fun documentIdIn(indexId: String): DocumentId? =
        runCatching { DocumentId(UUID.fromString(IndexedEntry.idIn(indexId))) }.getOrNull()

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
                highlight.fields(
                    listOf(
                        NamedValue.of("title", HighlightField.Builder().build()),
                        // A few short fragments rather than one long one: what a reader
                        // wants from a hundred-page file is the sentences, and three of
                        // them is enough to tell whether the file is the one.
                        NamedValue.of(
                            "content",
                            HighlightField.Builder().numberOfFragments(PASSAGES).fragmentSize(PASSAGE_LENGTH).build(),
                        ),
                    ),
                )
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
        .should(MatchQuery.of { it.field("content").query(text) }._toQuery())
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
            passages = hit.highlight()["content"].orEmpty(),
            eli = source["eli"]?.toString(),
            stage = source["stage"]?.toString(),
            outcome = source["outcome"]?.toString(),
            // Filled for the page as a whole, once the hits are known; see [withTheirDrafts].
            filedUnder = null,
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

        /** Fragments of a document's text shown per hit, and how long each may be. */
        const val PASSAGES = 3
        const val PASSAGE_LENGTH = 200
    }
}
