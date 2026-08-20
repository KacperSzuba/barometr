package pl.barometr.connectors.rcl

/**
 * A draft's or a catalog's event log, oldest entry first, as RPL renders it.
 *
 * One trap worth knowing before anyone builds on the `alias` attribute that appears
 * here: it is not a reliable marker of what a catalog is for. The catalog "Pisma
 * kierujące projekt do **konsultacji publicznych**" carries the alias
 * `pisma_uzgodnien` — copied from the Uzgodnienia stage's template and never
 * corrected. Classify catalogs by their name and position in the tree; the alias
 * will quietly disagree.
 */
data class RclChangeRegister(
    val subject: RclRegisterSubject?,
    val changes: List<RclChange>,
) {

    /**
     * Entries recording a move to a new stage.
     *
     * The one derivation worth making here rather than downstream: this is the only
     * place in the whole source where a stage transition carries a timestamp instead
     * of a date, and finding it means knowing that RPL words it as a change to the
     * attribute "nazwa etapu" — a fact about this source's phrasing, which is what a
     * connector is for.
     */
    val stageTransitions: List<RclChange>
        get() = changes.filter {
            it.kind == RclChangeKind.ATTRIBUTE_CHANGED && it.attribute == STAGE_ATTRIBUTE
        }

    /**
     * Catalogs filed beneath this one.
     *
     * The only machine-readable statement of the tree available today. A stage does
     * not hold documents directly: "Konsultacje publiczne" holds five catalogs of
     * its own, one of which is where the submitted comments end up. Without this the
     * walk would stop a level short of everything worth having.
     */
    val childCatalogs: List<RclChildCatalog>
        get() = changes.mapNotNull { change ->
            if (change.kind != RclChangeKind.CATALOG_ADDED) return@mapNotNull null
            val id = change.catalogId ?: return@mapNotNull null
            RclChildCatalog(id, change.catalogName ?: change.description)
        }

    /** Files filed under this catalog, with the minute each arrived. */
    val documentsFiled: List<RclChange>
        get() = changes.filter { it.kind == RclChangeKind.DOCUMENT_ADDED }

    private companion object {
        const val STAGE_ATTRIBUTE = "nazwa etapu"
    }
}
