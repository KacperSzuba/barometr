package pl.barometr.connectors.rcl

/**
 * How far and how wide the RPL walk goes.
 *
 * Grouped rather than passed as loose scalars, because they are one decision: how
 * much of the site a single call reads before its progress becomes durable. Passing
 * them separately made the connector's constructor a list of numbers whose order
 * nothing but the compiler was checking.
 */
data class RclWalkSettings(
    /** Rows per index page. RPL offers 10, 50, 100 and "all"; 100 is the largest sane one. */
    val pageSize: Int = DEFAULT_PAGE_SIZE,

    /**
     * Index pages read before a backfill call commits its cursor. One page of a
     * hundred drafts is roughly six hundred requests, so an interruption costs
     * minutes of repeated work rather than hours.
     */
    val pagesPerChunk: Int = DEFAULT_PAGES_PER_CHUNK,

    /**
     * How many levels of catalog to descend. One reaches a draft's stages; two
     * reaches the catalogs inside them, which is where the documents actually are —
     * a stage named "Konsultacje publiczne" holds five catalogs of its own, and the
     * submitted comments and the ministry's reply to them are two of the five.
     *
     * The setting exists because the level costs an order of magnitude. At depth
     * one a draft is roughly eight requests; at depth two it is closer to forty, so
     * a full replay of all twenty-four thousand drafts moves from about a week to
     * about two months at one request per five seconds. Which of those is right is
     * a question about the pace RPL has agreed to, not one this code should answer.
     */
    val catalogDepth: Int = DEFAULT_CATALOG_DEPTH,

    /**
     * Whether a catalog page is followed to the files filed under it.
     *
     * The expensive half of the walk and the valuable one. A stage holds a dozen
     * files where it holds one page, so switching this off roughly halves the
     * requests and gives up the draft texts, the impact assessments and the tables
     * of comments — everything the corpus is eventually made of.
     *
     * A setting rather than a constant for the same reason as [catalogDepth]: it is
     * a question about the pace RPL has agreed to, and an operator who needs to back
     * off should not have to wait for a release to do it.
     */
    val fetchAttachments: Boolean = DEFAULT_FETCH_ATTACHMENTS,
) {
    init {
        require(pageSize > 0) { "Page size must be positive" }
        require(pagesPerChunk > 0) { "A chunk must read at least one page" }
        require(catalogDepth >= 0) { "Catalog depth cannot be negative" }
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 100
        const val DEFAULT_PAGES_PER_CHUNK = 1
        const val DEFAULT_CATALOG_DEPTH = 2
        const val DEFAULT_FETCH_ATTACHMENTS = true
    }
}
