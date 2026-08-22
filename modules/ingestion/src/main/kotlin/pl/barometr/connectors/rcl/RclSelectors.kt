package pl.barometr.connectors.rcl

/**
 * CSS selectors describing where things sit on RPL's pages.
 *
 * Derived from saved pages, never from memory. That distinction is the whole reason
 * this class exists: a selector that looks plausible and matches nothing produces a
 * connector that runs happily and archives an empty set — a silent failure arrived
 * at on purpose. Everything below is pinned by a contract test against a real page
 * in `src/test/resources/fixtures/rcl`.
 *
 * Kept in configuration rather than inlined in the parsers so that a layout change
 * costs a YAML edit and a restart instead of a release — the same arrangement the
 * BIP framework will need later, where a handful of adapters have to cover thousands
 * of municipal sites differing only in markup.
 */
data class RclSelectors(
    val listing: Listing = Listing(),
    val projectCard: ProjectCard = ProjectCard(),
    val changeRegister: ChangeRegister = ChangeRegister(),
    val catalog: Catalog = Catalog(),
) {

    /** `/lista?typeId=…` — the paged index of drafts of one kind. */
    data class Listing(
        val row: String = "#lista table#table > tbody > tr",
        val projectLink: String = "td:eq(0) a[href*=/projekt/]",
        val applicant: String = "td:eq(1)",
        val registerNumber: String = "td:eq(2)",
        val createdAt: String = "td:eq(3)",
        val modifiedAt: String = "td:eq(4)",
        /**
         * Holds "Lista projektów według wybranych kryteriów: 2602".
         *
         * The total is read from here rather than from the pager, because the pager
         * is not trustworthy: on the regulations listing RPL renders a nested
         * `<html>` error document ("Strona nie istnieje.") in place of the page
         * links, so anything deriving a page count from them breaks on the largest
         * collection on the site.
         */
        val totalCount: String = "#list .col-sm-8",
    )

    /** `/projekt/{id}` — one draft: its metadata and the stages it has passed. */
    data class ProjectCard(
        val title: String = ".projectTitle .rcl-title",
        /** Label/value pairs; read by label, so reordering them changes nothing. */
        val metadataRow: String = "div.info > div.row",
        val metadataLabel: String = "div.col-xs-4",
        val metadataValue: String = "div.col-xs-6",
        val changeRegisterLink: String = "a[href*=/projekt/rejestr/projekt/]",
        val stageItem: String = "ul.cbp_tmtimeline > li",
        val stageLink: String = "a[href*=/katalog/]",
        /** Carries "Data ostatniej modyfikacji: 17-08-2026". */
        val stageModifiedAt: String = "div.small2",
    )

    /** `/projekt/rejestr/…` — the append-only event log of a draft or a stage. */
    data class ChangeRegister(
        val row: String = "table tbody tr",
        val occurredAt: String = "td:eq(0)",
        val author: String = "td:eq(1)",
        val event: String = "td:eq(2)",
    )

    /**
     * `/projekt/{id}/katalog/{catalogId}` — the documents filed under one stage.
     *
     * Written against a captured page at last, which closes the one step the walk was
     * missing: the change registers name a filed document and time it to the minute
     * but carry no link to it, so the archive knew a document existed without knowing
     * where to fetch it.
     *
     * The page renders the whole subtree inline — child catalogs as `li.childdir`
     * carrying their id in the `id` attribute, and every file beneath them as `li.doc`
     * regardless of depth. There is no separate title selector because there is no
     * separate title: the anchor's own text *is* the file name, and declaring that
     * fact twice would only create somewhere for the two to disagree.
     */
    data class Catalog(
        val documentRow: String = "li.doc",
        /** Carries the href and, as its text, the file name. */
        val documentLink: String = "a[href*=/docs/]",
        /**
         * The lines under a file: "Autor dokumentu: …" and "Data utworzenia: …".
         *
         * Read by label rather than by position, like the project card's metadata,
         * because RPL omits the author line on some filings.
         */
        val documentDetail: String = "div.small3",
        val childDirectory: String = "li.childdir",
        /** Carries "Data ostatniej modyfikacji: 18-08-2026". */
        val childDirectoryModifiedAt: String = "div.small2",
    )

    /**
     * Names of every selector still unset.
     *
     * Reported at startup rather than discovered at three in the morning, and
     * distinct from a crash: an unconfigured group means one page type is archived
     * but not followed, which is a reduced connector rather than a broken one.
     */
    fun missingFields(): List<String> = buildList {
        fun check(group: String, values: Map<String, String>) {
            values.filterValues { it.isBlank() }.keys.sorted().forEach { add("$group.$it") }
        }

        check(
            "listing",
            mapOf(
                "row" to listing.row,
                "projectLink" to listing.projectLink,
                "totalCount" to listing.totalCount,
            ),
        )
        check(
            "projectCard",
            mapOf(
                "title" to projectCard.title,
                "metadataRow" to projectCard.metadataRow,
                "stageItem" to projectCard.stageItem,
                "stageLink" to projectCard.stageLink,
            ),
        )
        check(
            "changeRegister",
            mapOf(
                "row" to changeRegister.row,
                "occurredAt" to changeRegister.occurredAt,
                "event" to changeRegister.event,
            ),
        )
        check(
            "catalog",
            mapOf(
                "documentRow" to catalog.documentRow,
                "documentLink" to catalog.documentLink,
                "childDirectory" to catalog.childDirectory,
            ),
        )
    }

    /** True once even the catalog pages can be followed to their attachments. */
    val isConfigured: Boolean get() = missingFields().isEmpty()

    /** Enough to walk the site and archive every page, short of the attachments. */
    val canWalkSite: Boolean
        get() = missingFields().none { !it.startsWith("catalog.") }
}
