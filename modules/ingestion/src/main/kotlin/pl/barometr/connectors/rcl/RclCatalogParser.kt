package pl.barometr.connectors.rcl

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import pl.barometr.connectors.rcl.api.RclCatalogPage
import pl.barometr.connectors.rcl.api.RclChildDirectory
import pl.barometr.connectors.rcl.api.RclFiledDocument

/**
 * Reads a stage's catalog page: the folders it is divided into, and every file in
 * them.
 *
 * A file's catalog is taken from its own href rather than from where the row sits in
 * the markup. The page nests each `li.doc` in a `ul` of its own beside the
 * `li.childdir` it belongs to, so position would work — until RPL reflows the list, at
 * which point every document in the archive would quietly change folders. The href
 * states the same fact and is the one the site itself resolves by.
 *
 * A row this cannot place is dropped rather than half-read. There is no useful partial
 * file: without an id there is nothing to address it by, and without an href there is
 * nothing to fetch.
 */
class RclCatalogParser(
    private val selectors: RclSelectors.Catalog = RclSelectors.Catalog(),
) {

    fun readCatalog(page: Document): RclCatalogPage = RclCatalogPage(
        childDirectories = page.select(selectors.childDirectory).mapNotNull(::readChildDirectory),
        documents = page.select(selectors.documentRow).mapNotNull(::readFiledDocument),
    )

    /**
     * The name is the element's *own* text: the modification date sits in a `div`
     * inside the same `li`, so the whole subtree's text would read
     * "Projekt Data ostatniej modyfikacji: 18-08-2026".
     */
    private fun readChildDirectory(item: Element): RclChildDirectory? {
        val catalogId = item.id().takeIf { it.isNotBlank() } ?: return null
        val modifiedAt = item.selectFirst(selectors.childDirectoryModifiedAt)?.text().orEmpty()

        return RclChildDirectory(
            catalogId = catalogId,
            name = item.ownText().unpadded(),
            lastModifiedAt = RclDateFormats.readDate(DAY_FIRST_DATE.find(modifiedAt)?.value),
        )
    }

    private fun readFiledDocument(row: Element): RclFiledDocument? {
        val link = row.selectFirst(selectors.documentLink) ?: return null
        val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
        val documentId = RclIdentifiers.documentIdIn(href) ?: return null
        val catalogId = RclIdentifiers.catalogIdOfFileIn(href) ?: return null

        // Each line is matched on its own rather than on the lot joined together. A
        // filing without an author is common, and against joined text a pattern that
        // stops at "end of string" would swallow the creation date into the name.
        val detail = row.select(selectors.documentDetail).map { it.text().unpadded() }

        return RclFiledDocument(
            documentId = documentId,
            catalogId = catalogId,
            fileName = link.text().unpadded(),
            href = href,
            author = detail.firstNotNullOfOrNull { AUTHOR.find(it) }
                ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
            createdOn = RclDateFormats.readDate(
                detail.firstNotNullOfOrNull { CREATED_ON.find(it) }?.groupValues?.get(1),
            ),
        )
    }

    /**
     * RPL indents these lines with `&nbsp;`, which is not whitespace to anything that
     * trims. Left in, every author would arrive with three invisible characters in
     * front of it and no two readings of the same name would compare equal.
     */
    private fun String.unpadded(): String = replace(NBSP, ' ').trim()

    private companion object {
        /** The non-breaking space RPL indents with. */
        const val NBSP = '\u00A0'

        val DAY_FIRST_DATE = Regex("""\d{2}-\d{2}-\d{4}""")

        /** "Autor dokumentu: Minister Sprawiedliwości , wprowadzony przez: Aneta Sobolewska". */
        val AUTHOR = Regex("""Autor dokumentu:\s*(.*?)\s*(?:,\s*wprowadzony przez:|$)""")
        val CREATED_ON = Regex("""Data utworzenia:\s*(\d{2}-\d{2}-\d{4})""")
    }
}
