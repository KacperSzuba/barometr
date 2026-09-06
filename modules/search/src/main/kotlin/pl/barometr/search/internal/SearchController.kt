package pl.barometr.search.internal

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Searching acts and drafts.
 *
 * Any authenticated caller may search: this is the product's own view of a public
 * legislative process, and there is nothing here a signed-up user should not see.
 *
 * Filters arrive as repeated query parameters rather than a body, so that a search is
 * a link somebody can send to a colleague — which is most of what makes a search
 * result useful inside an organisation.
 */
@RestController
@RequestMapping("/api/v1/search")
class SearchController(private val search: LegislativeSearch) {

    @GetMapping
    fun search(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) kind: Set<String>?,
        @RequestParam(required = false) stage: Set<String>?,
        @RequestParam(required = false) initiator: Set<String>?,
        @RequestParam(required = false) actType: Set<String>?,
        @RequestParam(defaultValue = "0") from: Int,
        @RequestParam(defaultValue = "${SearchQuery.DEFAULT_SIZE}") size: Int,
    ): SearchResponse {
        val results = search.search(
            SearchQuery(
                text = q,
                kinds = kind.orEmpty(),
                stages = stage.orEmpty(),
                initiators = initiator.orEmpty(),
                actTypes = actType.orEmpty(),
                from = from,
                size = size,
            ),
        )

        return SearchResponse(
            total = results.total,
            hits = results.hits.map {
                HitResponse(
                    id = it.id,
                    kind = it.kind,
                    title = it.title,
                    highlightedTitle = it.highlightedTitle,
                    passages = it.passages,
                    eli = it.eli,
                    stage = it.stage,
                    outcome = it.outcome,
                    filedUnder = it.filedUnder?.let { filed ->
                        FiledUnderResponse(
                            draftId = filed.draftId.value,
                            draftTitle = filed.draftTitle,
                            fileName = filed.fileName,
                        )
                    },
                    score = it.score,
                )
            },
            facets = results.facets,
        )
    }

    data class SearchResponse(
        val total: Long,
        val hits: List<HitResponse>,
        /** Counts per value, per field, for narrowing the same search down. */
        val facets: Map<String, Map<String, Long>>,
    )

    data class HitResponse(
        val id: String,
        /** `ustawa`, `projekt`, or `document` for a file filed under a draft. */
        val kind: String,
        val title: String,
        /** The title with the matching words marked, when the match was on the title. */
        val highlightedTitle: String?,
        /**
         * Sentences from the file with the matching words marked, when the match was
         * inside one. Empty for everything else.
         */
        val passages: List<String>,
        val eli: String?,
        val stage: String?,
        val outcome: String?,
        /** For a document, the draft it was filed under — which is how a reader gets from a passage to the bill. */
        val filedUnder: FiledUnderResponse?,
        val score: Double,
    )

    /** The draft a matching file belongs to, and what the file is called. */
    data class FiledUnderResponse(
        val draftId: java.util.UUID,
        val draftTitle: String,
        /** RPL's name for the file: what tells a reader this is the bill and not the comments on it. */
        val fileName: String?,
    )
}
