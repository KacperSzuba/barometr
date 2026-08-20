package pl.barometr.connectors.rcl

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

/**
 * How far a draft has got with one stage, as RPL itself renders it.
 *
 * Three states rather than a progression, because RPL does not enforce one. On a
 * real card in the fixtures, "Uzgodnienia" is finished, "Konsultacje publiczne" was
 * never started, and "Opiniowanie" is under way — a stage skipped outright while a
 * later one runs. Any model that treats these as an ordered pipeline will
 * misrepresent that draft, which is why the state recorded here is the site's claim
 * and nothing more.
 */
enum class RclStageState {
    /** The stage exists but holds nothing yet. */
    NOT_STARTED,

    /** Where the draft is now. */
    CURRENT,

    /** Rendered as passed: it holds documents and is not the current stage. */
    DONE,

    /** The markup said something this parser does not recognise. */
    UNKNOWN,
}

/**
 * One stage of a draft's passage.
 *
 * Every stage has a [catalogId] from the moment the draft is created — the change
 * register shows all of them filed within three minutes of each other — so the id
 * is present even for stages nothing has happened in. [isVisitable] is the useful
 * distinction: it says whether RPL links the stage, and therefore whether there is
 * anything behind it to fetch.
 */
data class RclStage(
    val catalogId: String,
    /** The number RPL prints before the name; absent if the name is unnumbered. */
    val ordinal: Int?,
    val name: String,
    val state: RclStageState,
    val lastModifiedAt: LocalDate?,
    val isVisitable: Boolean,
)

/** A draft's page, read into the parts worth acting on. */
data class RclProjectCard(
    val projectId: String,
    val title: String,
    /** Keyed by the label RPL prints, with the trailing colon removed. */
    val metadata: Map<String, String>,
    /** Deep link to the ministry's programme of work, when the card carries one. */
    val programmeOfWorkUrl: String?,
    val stages: List<RclStage>,
) {
    val applicant: String? get() = metadata[APPLICANT]
    val status: String? get() = metadata[STATUS]
    val registerNumber: String? get() = metadata[REGISTER_NUMBER]
    val term: String? get() = metadata[TERM]

    /** Departments and keywords arrive comma-joined in a single cell. */
    val departments: List<String> get() = splitList(metadata[DEPARTMENTS])
    val keywords: List<String> get() = splitList(metadata[KEYWORDS])

    /** The stages worth fetching: the ones RPL links because they hold something. */
    val visitableStages: List<RclStage> get() = stages.filter { it.isVisitable }

    private fun splitList(value: String?): List<String> =
        value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        const val APPLICANT = "Wnioskodawca"
        const val CREATED_AT = "Data utworzenia"
        const val DEPARTMENTS = "Działy"
        const val KEYWORDS = "Hasła"
        const val STATUS = "Status projektu"
        const val PROGRAMME_OF_WORK = "Wykaz prac legislacyjnych"
        const val REGISTER_NUMBER = "Numer z wykazu"
        const val TERM = "Kadencja"
        const val TERM_PERIOD = "Okres kadencji"
    }
}

/**
 * Reads a draft's page.
 *
 * Metadata is read by label rather than by position, so RPL adding, removing or
 * reordering a row changes nothing here — and it does vary between cards: a
 * regulation carries a "Numer z wykazu" pointing at its ministry's programme, a
 * bill filed outside any programme carries none.
 */
class RclProjectCardParser(
    private val selectors: RclSelectors.ProjectCard = RclSelectors.ProjectCard(),
) {

    fun readProjectCard(page: Document): RclProjectCard? {
        val projectId = readProjectId(page) ?: return null
        val metadata = readMetadata(page)

        return RclProjectCard(
            projectId = projectId,
            title = page.selectFirst(selectors.title)?.text().orEmpty().trim(),
            metadata = metadata.values,
            programmeOfWorkUrl = metadata.links[RclProjectCard.REGISTER_NUMBER],
            stages = page.select(selectors.stageItem).mapNotNull(::readStage),
        )
    }

    /**
     * The card never states its own id in a field, so it is recovered from a link
     * that must contain it — the change register first, since every card has one,
     * and a stage link only as a fallback.
     */
    private fun readProjectId(page: Document): String? {
        page.selectFirst(selectors.changeRegisterLink)
            ?.let { RclIdentifiers.projectIdIn(it.attr("href")) }
            ?.let { return it }

        return page.select(selectors.stageLink)
            .firstNotNullOfOrNull { RclIdentifiers.projectIdIn(it.attr("href")) }
    }

    private class Metadata(val values: Map<String, String>, val links: Map<String, String>)

    private fun readMetadata(page: Document): Metadata {
        val values = LinkedHashMap<String, String>()
        val links = LinkedHashMap<String, String>()

        page.select(selectors.metadataRow).forEach { row ->
            val label = row.selectFirst(selectors.metadataLabel)?.text()
                ?.trim()?.removeSuffix(":")?.trim()
                ?: return@forEach
            val value = row.selectFirst(selectors.metadataValue) ?: return@forEach
            if (label.isEmpty()) return@forEach

            values[label] = value.text().trim().trim(',').trim()
            value.selectFirst("a[href]")?.let { links[label] = it.attr("href") }
        }
        return Metadata(values, links)
    }

    private fun readStage(item: Element): RclStage? {
        val catalogId = item.id().takeIf { it.isNotBlank() }
            ?: item.selectFirst(selectors.stageLink)
                ?.let { RclIdentifiers.catalogIdIn(it.attr("href")) }
            ?: return null

        val link = item.selectFirst(selectors.stageLink)
        val heading = link?.text() ?: item.selectFirst(LABEL_BLOCK)?.selectFirst("div")?.text()
        val numbered = STAGE_HEADING.find(heading.orEmpty().trim())

        return RclStage(
            catalogId = catalogId,
            ordinal = numbered?.groupValues?.get(1)?.toIntOrNull(),
            name = numbered?.groupValues?.get(2)?.trim() ?: heading.orEmpty().trim(),
            state = readStageState(item),
            lastModifiedAt = readStageModifiedAt(item),
            isVisitable = link != null,
        )
    }

    private fun readStageState(item: Element) = when {
        item.selectFirst("div.cbp_tmlabel_notstart") != null -> RclStageState.NOT_STARTED
        item.selectFirst("div.cbp_tmlabel_active") != null -> RclStageState.CURRENT
        // Matches only the bare class: jsoup compares whole class tokens, so this
        // cannot be reached by the two suffixed variants above.
        item.selectFirst("div.cbp_tmlabel") != null -> RclStageState.DONE
        else -> RclStageState.UNKNOWN
    }

    private fun readStageModifiedAt(item: Element): LocalDate? {
        val text = item.selectFirst(selectors.stageModifiedAt)?.text().orEmpty()
        return RclDates.readDate(DAY_FIRST_DATE.find(text)?.value)
    }

    private companion object {
        const val LABEL_BLOCK = "div[class^=cbp_tmlabel]"
        val STAGE_HEADING = Regex("""^(\d+)\.\s*(.*)$""")
        val DAY_FIRST_DATE = Regex("""\d{2}-\d{2}-\d{4}""")
    }
}
