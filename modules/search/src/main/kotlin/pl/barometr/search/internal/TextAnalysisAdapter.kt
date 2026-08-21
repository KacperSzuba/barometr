package pl.barometr.search.internal

import co.elastic.clients.elasticsearch.ElasticsearchClient
import org.springframework.stereotype.Component
import pl.barometr.search.api.TextAnalysis

/**
 * Stemming, asked of the node rather than reproduced here.
 *
 * The alternative is a copy of the analyser chain in Kotlin — Stempel, the stopword
 * list, the legal-form overrides — which would have to be kept in step with
 * `legislative-index.json` by somebody remembering to. This costs one call and cannot
 * drift.
 *
 * It fails loudly when the index is not there. A caller matching keywords against a
 * missing analyser would otherwise decide that nothing matches, and "no alerts" is
 * indistinguishable from "a quiet week".
 */
@Component
class TextAnalysisAdapter(private val client: ElasticsearchClient) : TextAnalysis {

    override fun stemsOf(text: String): List<String> =
        client.indices()
            .analyze { it.index(LegislativeIndex.ALIAS).analyzer(LegislativeIndex.ANALYZER).text(text) }
            .tokens()
            .map { it.token() }
}
