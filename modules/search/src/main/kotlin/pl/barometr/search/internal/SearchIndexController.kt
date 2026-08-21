package pl.barometr.search.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The one command that makes a derived index safe to keep: build it again from
 * Postgres.
 *
 * Operator-only, and not because it is dangerous — it cannot lose anything, since the
 * index holds nothing that is not derived — but because it walks every act and draft
 * there is and writes them all. That is somebody's afternoon of I/O, and registration
 * is open.
 */
@RestController
@RequestMapping("/api/v1/search/index")
@PreAuthorize("hasRole('OPERATOR')")
class SearchIndexController(private val rebuild: SearchIndexRebuild) {

    @PostMapping("/rebuild")
    fun rebuildIndex(): RebuildResponse {
        val report = rebuild.rebuild()

        return RebuildResponse(index = report.index, acts = report.acts, drafts = report.drafts)
    }

    data class RebuildResponse(val index: String, val acts: Int, val drafts: Int)
}
