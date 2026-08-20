package pl.barometr.connectors.rcl

import java.net.URI

/**
 * The four kinds of draft RPL publishes, and the `typeId` each answers to.
 *
 * These four are chosen to tile the site exactly once. RPL also exposes narrower
 * ids — 3, 4, 5 and 7 for the Council of Ministers, the Prime Minister, individual
 * ministers and statutory committees — but every one of those is already contained
 * in [REGULATIONS] (10), which the site's own menu presents as their parent. Walking
 * both levels would archive each regulation twice under two different partitions.
 */
enum class RclProjectType(val typeId: Int, val slug: String, val label: String) {
    ASSUMPTIONS(1, "zalozenia", "Projekty założeń projektów ustaw"),
    BILLS(2, "ustawy", "Projekty ustaw"),
    REGULATIONS(10, "rozporzadzenia", "Projekty rozporządzeń"),
    IMPACT_REVIEWS(6, "osr", "OSR ex post"),
    ;

    companion object {
        fun ofSlug(slug: String): RclProjectType? = entries.firstOrNull { it.slug == slug }
    }
}

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
class RclPages(private val baseUrl: URI) {

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

/**
 * How an RPL page is addressed in our archive.
 *
 * In one place because these strings are the idempotency key: change how a project
 * card is addressed and every card in the archive re-ingests as if it were new.
 *
 * The draft's kind is folded into the id even though RPL's own URLs leave it out.
 * That is what makes a completeness audit possible: the archive can be counted one
 * partition at a time, against the tally RPL prints for that kind. It is safe to
 * bake in because a draft's kind is intrinsic rather than a status — a bill never
 * becomes a regulation — so the id it produces is stable for the document's life.
 */
object RclExternalIds {

    fun project(type: RclProjectType, projectId: String) =
        ingestionId("${projectPrefix(type)}$projectId")

    fun projectChangeRegister(type: RclProjectType, projectId: String) =
        ingestionId("${projectPrefix(type)}$projectId/rejestr")

    fun catalog(type: RclProjectType, projectId: String, catalogId: String) =
        ingestionId("${projectPrefix(type)}$projectId/katalog/$catalogId")

    fun catalogChangeRegister(type: RclProjectType, projectId: String, catalogId: String) =
        ingestionId("${projectPrefix(type)}$projectId/katalog/$catalogId/rejestr")

    /**
     * Counting prefix for one partition's project cards.
     *
     * Pairs with the archive's "directly under" count, so the registers and catalog
     * pages nested beneath a card — `…/12409051/rejestr` and
     * `…/12409051/katalog/13196866` — are not mistaken for cards of their own.
     */
    fun projectPrefix(type: RclProjectType): String = "projekt/${type.slug}/"

    private fun ingestionId(value: String) = pl.barometr.ingestion.api.ExternalId(value)
}
