package pl.barometr.connectors.rcl

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime

/** What a register entry did, as far as its wording can be trusted. */
enum class RclChangeKind {
    PROJECT_CREATED,
    CATALOG_CREATED,

    /** A child catalog was filed under this one — how the tree is discovered. */
    CATALOG_ADDED,

    /** A file was filed under this catalog. The event this whole source exists for. */
    DOCUMENT_ADDED,

    ATTRIBUTE_CHANGED,
    OTHER,
}

/** Whether a register describes a draft or one of its catalogs. */
enum class RclRegisterScope { PROJECT, CATALOG }

/**
 * What a register is about, and what it hangs beneath.
 *
 * Worth reading because it is the only statement of the tree's shape that RPL
 * makes in prose: a catalog register says either `w projekcie "…"` or
 * `w katalogu "…"`, and the difference is what reveals that stages contain
 * catalogs of their own rather than documents directly.
 */
data class RclRegisterSubject(
    val scope: RclRegisterScope,
    val name: String,
    val parentScope: RclRegisterScope? = null,
    val parentName: String? = null,
    /** Project id or catalog id, taken from the link beside the heading. */
    val parentId: String? = null,
)

/** A catalog filed under another, as announced by its parent's register. */
data class RclChildCatalog(val catalogId: String, val name: String)

/**
 * One entry in a draft's or a catalog's event log.
 *
 * [occurredAt] is accurate to the minute, which is the reason these pages are
 * fetched at all. A project card says a stage was last touched on some date; the
 * register says it changed at 15:24 that day, and a bitemporal record wants the
 * latter for its `valid_from`.
 */
data class RclChange(
    val occurredAt: LocalDateTime?,
    /**
     * Who made the change. Sometimes an institution — "Minister Sprawiedliwości",
     * "Administrator" — and sometimes a named civil servant.
     *
     * The named case is personal data published by RPL itself. It travels through
     * the connector because dropping it here would silently break provenance, but
     * what the system retains and displays is a separate decision that belongs with
     * the source's recorded legal basis, not with a parser.
     */
    val author: String,
    val description: String,
    val kind: RclChangeKind,
    /** For [RclChangeKind.ATTRIBUTE_CHANGED]: which attribute, and its new value. */
    val attribute: String? = null,
    val newValue: String? = null,
    /** For [RclChangeKind.CATALOG_ADDED]: the child catalog's name. */
    val catalogName: String? = null,
    /** For [RclChangeKind.DOCUMENT_ADDED]: the file name, as filed. */
    val documentName: String? = null,
    /** Set when the entry links the catalog it concerns. */
    val catalogId: String? = null,
)

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

/** Reads the append-only event log behind a draft or one of its catalogs. */
class RclChangeRegisterParser(
    private val selectors: RclSelectors.ChangeRegister = RclSelectors.ChangeRegister(),
) {

    fun readChangeRegister(page: Document) = RclChangeRegister(
        subject = readSubject(page),
        changes = page.select(selectors.row).mapNotNull(::readChange),
    )

    /**
     * The heading is a bare text node with no class or id of its own, so it is found
     * by its wording rather than by a selector. Ugly, and still the sturdier choice:
     * the sentence is what RPL renders for a human to read, and it will outlive any
     * particular arrangement of the wrapper divs around it.
     */
    private fun readSubject(page: Document): RclRegisterSubject? {
        val headingElement = page.allElements
            .asSequence()
            .firstOrNull { it.ownText().trim().startsWith(HEADING_PREFIX) }
            ?: return null
        val heading = headingElement.ownText().trim()

        // Scoped to the heading's own block and taken first, because the rows below
        // link to registers too. The heading's link precedes them in document order,
        // so "first within this block" is the parent and nothing else is.
        val parentId = headingElement.selectFirst("a[href*=/projekt/rejestr/]")
            ?.attr("href")
            ?.let { href -> RclIdentifiers.catalogIdIn(href) ?: RclIdentifiers.projectIdIn(href) }

        CATALOG_IN_PROJECT.find(heading)?.let { match ->
            return RclRegisterSubject(
                scope = RclRegisterScope.CATALOG,
                name = match.groupValues[1],
                parentScope = RclRegisterScope.PROJECT,
                parentName = match.groupValues[2],
                parentId = parentId,
            )
        }
        CATALOG_IN_CATALOG.find(heading)?.let { match ->
            return RclRegisterSubject(
                scope = RclRegisterScope.CATALOG,
                name = match.groupValues[1],
                parentScope = RclRegisterScope.CATALOG,
                parentName = match.groupValues[2],
                parentId = parentId,
            )
        }
        return PROJECT_REGISTER.find(heading)?.let { match ->
            RclRegisterSubject(RclRegisterScope.PROJECT, match.groupValues[1])
        }
    }

    private fun readChange(row: Element): RclChange? {
        val eventCell = row.selectFirst(selectors.event) ?: return null

        // The wording and the "(rejestr)" link share a cell, so the link is taken
        // first and then removed: leaving it in would append "(rejestr)" to every
        // description and make the wording patterns below fail to match.
        val catalogId = eventCell.selectFirst("a[href*=/katalog/]")
            ?.let { RclIdentifiers.catalogIdIn(it.attr("href")) }
        val description = eventCell.clone().also { it.select("a").remove() }.text().trim()
        if (description.isEmpty()) return null

        val attributeChange = ATTRIBUTE_CHANGE.find(description)
        val catalogAdded = CATALOG_ADDED.find(description)
        val documentAdded = DOCUMENT_ADDED.find(description)

        return RclChange(
            occurredAt = RclDates.readTimestamp(row.selectFirst(selectors.occurredAt)?.text()),
            author = row.selectFirst(selectors.author)?.text().orEmpty().trim(),
            description = description,
            kind = classify(description, attributeChange, catalogAdded, documentAdded),
            attribute = attributeChange?.groupValues?.get(2)?.trim(),
            newValue = attributeChange?.groupValues?.get(3)?.trim(),
            catalogName = catalogAdded?.groupValues?.get(1)?.trim(),
            documentName = documentAdded?.groupValues?.get(1)?.trim(),
            catalogId = catalogId,
        )
    }

    private fun classify(
        description: String,
        attributeChange: MatchResult?,
        catalogAdded: MatchResult?,
        documentAdded: MatchResult?,
    ) = when {
        attributeChange != null -> RclChangeKind.ATTRIBUTE_CHANGED
        documentAdded != null -> RclChangeKind.DOCUMENT_ADDED
        catalogAdded != null -> RclChangeKind.CATALOG_ADDED
        description.startsWith("Stworzono projekt") -> RclChangeKind.PROJECT_CREATED
        description.startsWith("Stworzono katalog") -> RclChangeKind.CATALOG_CREATED
        else -> RclChangeKind.OTHER
    }

    private companion object {
        const val HEADING_PREFIX = "Rejestr zdarzeń dla"

        val PROJECT_REGISTER = Regex("""^Rejestr zdarzeń dla projektu "(.+)"$""")
        val CATALOG_IN_PROJECT = Regex("""^Rejestr zdarzeń dla katalogu "(.+?)" w projekcie "(.+)"$""")
        val CATALOG_IN_CATALOG = Regex("""^Rejestr zdarzeń dla katalogu "(.+?)" w katalogu "(.+)"$""")

        /** "Zmieniono atrybut projektu nazwa etapu na:  2. Uzgodnienia" */
        val ATTRIBUTE_CHANGE = Regex("""^Zmieniono atrybut (projektu|katalogu) (.+?) na:\s*(.*)$""")
        val CATALOG_ADDED = Regex("""^Dodano katalog\s+(.+)$""")
        val DOCUMENT_ADDED = Regex("""^Dodano dokument\s+(.+)$""")
    }
}
