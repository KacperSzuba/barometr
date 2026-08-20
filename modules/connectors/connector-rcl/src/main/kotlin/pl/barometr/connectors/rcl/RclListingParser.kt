package pl.barometr.connectors.rcl

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

/** One row of a draft index: enough to decide whether the draft is worth visiting. */
data class RclListingEntry(
    val projectId: String,
    val title: String,
    val applicant: String,
    /**
     * The draft's number in its ministry's programme of work — `UD412`, `RD319`,
     * `MZ1921`. Null for drafts filed outside any programme.
     */
    val registerNumber: String?,
    val createdAt: LocalDate?,
    val modifiedAt: LocalDate?,
)

/**
 * One page of an index, and how much of the collection lies beyond it.
 *
 * [totalCount] comes from the site's own tally rather than from counting rows,
 * which is what lets the walk know how far it has to go before it starts.
 */
data class RclListingPage(
    val totalCount: Int,
    val entries: List<RclListingEntry>,
) {
    val isEmpty: Boolean get() = entries.isEmpty()

    /** How many pages this collection spans at [pageSize] rows each. */
    fun pageCount(pageSize: Int): Int =
        if (totalCount <= 0) 0 else (totalCount + pageSize - 1) / pageSize
}

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
            createdAt = RclDates.readDate(row.selectFirst(selectors.createdAt)?.text()),
            modifiedAt = RclDates.readDate(row.selectFirst(selectors.modifiedAt)?.text()),
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

/** Draft and stage ids, pulled out of the hrefs RPL uses to link them. */
object RclIdentifiers {

    private val PROJECT = Regex("""/projekt/(\d+)""")
    private val CATALOG = Regex("""/katalog/(\d+)""")

    fun projectIdIn(href: String): String? = PROJECT.find(href)?.groupValues?.get(1)

    fun catalogIdIn(href: String): String? = CATALOG.find(href)?.groupValues?.get(1)
}
