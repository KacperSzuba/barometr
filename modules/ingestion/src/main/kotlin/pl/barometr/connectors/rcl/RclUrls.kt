package pl.barometr.connectors.rcl

import java.net.URI

/**
 * Every URL the connector asks for, built in one place.
 *
 * Worth its own class because the listing parameters are not decoration — they
 * decide whether a walk is correct. Sorting a list by *creation* ascending makes it
 * append-only, so page 7 holds the same drafts tomorrow as today and a paged
 * backfill can be interrupted anywhere. Sorting the same list the other way, which
 * is the site's own default, would shift every row down as drafts are added and a
 * resumed walk would skip whatever slid across a page boundary.
 */
class RclUrls(private val baseUrl: URI) {

    /**
     * Oldest first, so paging is stable under concurrent additions. Used by the
     * backfill, which cares about reading everything exactly once.
     */
    fun oldestFirstListing(type: RclProjectType, page: Int, pageSize: Int): URI =
        listing(type, page, pageSize, sortKey = SORT_BY_CREATED, ascending = true)

    /**
     * Most recently touched first, so an incremental pass can stop as soon as it
     * reaches drafts it already knows. Sorted by *modification*, not creation: a
     * draft filed two years ago that moved to public consultation this morning is
     * exactly the event this system exists to catch, and it sits near the bottom of
     * any creation-ordered list.
     */
    fun recentlyChangedListing(type: RclProjectType, page: Int, pageSize: Int): URI =
        listing(type, page, pageSize, sortKey = SORT_BY_MODIFIED, ascending = false)

    private fun listing(
        type: RclProjectType,
        page: Int,
        pageSize: Int,
        sortKey: String,
        ascending: Boolean,
    ): URI {
        require(page >= 1) { "Pages are numbered from one, got $page" }
        require(pageSize >= 1) { "Page size must be positive, got $pageSize" }
        return resolve(
            "/lista?typeId=${type.typeId}" +
                "&sKey=$sortKey&sOrder=${if (ascending) "asc" else "desc"}" +
                "&pSize=$pageSize&pNumber=$page",
        )
    }

    fun project(projectId: String): URI = resolve("/projekt/$projectId")

    fun catalog(projectId: String, catalogId: String): URI =
        resolve("/projekt/$projectId/katalog/$catalogId")

    fun projectChangeRegister(projectId: String): URI =
        resolve("/projekt/rejestr/projekt/$projectId")

    fun catalogChangeRegister(catalogId: String): URI =
        resolve("/projekt/rejestr/katalog/$catalogId")

    /** Relative hrefs scraped out of a page, resolved against the site root. */
    fun resolve(href: String): URI = baseUrl.resolve(href)

    private companion object {
        const val SORT_BY_CREATED = "createDate"
        const val SORT_BY_MODIFIED = "modifiedDate"
    }
}
