package pl.barometr.connectors.rcl

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
            occurredAt = RclDateFormats.readTimestamp(row.selectFirst(selectors.occurredAt)?.text()),
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
