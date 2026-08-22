package pl.barometr.connectors.rcl.api

/**
 * A stage's catalog page, read into the two things behind it.
 *
 * RPL renders the whole subtree inline: opening "Konsultacje publiczne" shows its five
 * child catalogs *and* every file in them, so one page answers what the connector
 * previously had to reconstruct by descending through change registers.
 *
 * The connector still descends, because only a register timestamps a filing to the
 * minute — but it now knows, from the page it just archived, which files to fetch. A
 * file therefore appears on both the parent's page and its child's, which is why the
 * walk deduplicates by [RclFiledDocument.documentId] rather than trusting the tree.
 */
data class RclCatalogPage(
    val childDirectories: List<RclChildDirectory>,
    val documents: List<RclFiledDocument>,
)
