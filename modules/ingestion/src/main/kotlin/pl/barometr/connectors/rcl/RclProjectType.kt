package pl.barometr.connectors.rcl

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
