package pl.barometr.connectors.rcl

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

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
        return RclDateFormats.readDate(DAY_FIRST_DATE.find(text)?.value)
    }

    private companion object {
        const val LABEL_BLOCK = "div[class^=cbp_tmlabel]"
        val STAGE_HEADING = Regex("""^(\d+)\.\s*(.*)$""")
        val DAY_FIRST_DATE = Regex("""\d{2}-\d{2}-\d{4}""")
    }
}
