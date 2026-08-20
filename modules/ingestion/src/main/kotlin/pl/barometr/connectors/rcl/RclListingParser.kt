package pl.barometr.connectors.rcl

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Reads a draft index page.
 *
 * Deliberately tolerant of rows it cannot make sense of: a row without a project
 * link is skipped rather than fatal. RPL renders a nested `<html>` error document
 * inside the pager of its largest listing, which is a standing reminder that this
 * markup is not a contract and a parser that insists on it will fail on a Tuesday.
 */
class RclListingParser(private val selectors: RclSelectors.Listing = RclSelectors.Listing()) {

    fun readListing(page: Document): RclListingPage = RclListingPage(
        totalCount = readTotalCount(page),
        entries = page.select(selectors.row).mapNotNull(::readEntry),
    )

    private fun readEntry(row: Element): RclListingEntry? {
        val link = row.selectFirst(selectors.projectLink) ?: return null
        val projectId = RclIdentifiers.projectIdIn(link.attr("href")) ?: return null

        return RclListingEntry(
            projectId = projectId,
            title = link.text().trim(),
            applicant = row.selectFirst(selectors.applicant)?.text().orEmpty().trim(),
            registerNumber = row.selectFirst(selectors.registerNumber)?.text()?.trim()
                ?.takeIf { it.isNotEmpty() },
            createdAt = RclDateFormats.readDate(row.selectFirst(selectors.createdAt)?.text()),
            modifiedAt = RclDateFormats.readDate(row.selectFirst(selectors.modifiedAt)?.text()),
        )
    }

    /** Pulls 2602 out of "Lista projektów według wybranych kryteriów: 2602". */
    private fun readTotalCount(page: Document): Int {
        val header = page.selectFirst(selectors.totalCount)?.text().orEmpty()
        return TRAILING_NUMBER.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private companion object {
        val TRAILING_NUMBER = Regex("""(\d+)\s*$""")
    }
}
