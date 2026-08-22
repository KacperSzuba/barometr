package pl.barometr.connectors.rcl

import pl.barometr.ingestion.api.RawDocumentSink

/**
 * One draft's walk: what is being read, where the payloads go, and what has already
 * been reached.
 *
 * It exists because the alternative was threading the same four values through every
 * private method on the connector. `visitCatalog` was already at six parameters before
 * the catalog step added two more, and a method that long stops describing a walk and
 * starts describing its own bookkeeping.
 *
 * Both guards are per draft rather than per run. Two drafts sharing a catalog would be
 * a bug in RPL, and a run holding every id it has ever seen would grow without bound
 * across a backfill of twenty-four thousand drafts.
 */
class RclDraftWalk(
    val type: RclProjectType,
    val projectId: String,
    val sink: RawDocumentSink,
) {
    private val catalogsReached = mutableSetOf<String>()
    private val documentsFetched = mutableSetOf<String>()

    /**
     * True the first time this catalog is reached, and records it.
     *
     * The tree is discovered from scraped links, and recursion driven by those should
     * not be able to spin forever on a register that names an ancestor. RPL has no
     * reason to produce one; the guard costs a set.
     */
    fun entersCatalog(catalogId: String): Boolean = catalogsReached.add(catalogId)

    /**
     * True the first time this file is reached, and records it.
     *
     * A catalog page renders its whole subtree, so every file appears again on each
     * page above the one it is filed in. The sink would recognise the repeat and store
     * nothing, but at one request every five seconds the fetch it saves is worth more
     * than the write.
     */
    fun fetchesDocument(documentId: String): Boolean = documentsFetched.add(documentId)
}
