package pl.barometr.corpus.internal.diff

import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component
import pl.barometr.corpus.api.DocumentTextExtracted

/**
 * Queues the comparisons that a version's text has just made possible.
 *
 * Both of them, not one. Text arrives in whatever order payloads are extracted, so the
 * version that has just become readable may be the newer half of a pair whose older
 * half has been waiting for months, or the older half of one whose successor was
 * derived first. Asking only about the predecessor would leave the second case to the
 * sweep, hours later, for no reason.
 *
 * It listens rather than being called, for the reason the extractor does: the
 * publication is written in the extractor's transaction, this runs in its own, and a
 * failure here leaves a row Spring Modulith redelivers rather than a version whose
 * changes silently nobody computed. Redelivery is free — the dedup key drops a
 * comparison already queued, and the unique index drops one already recorded.
 */
@Component
class ArrivingTextQueuesComparison(
    private val diffs: VersionDiffRepository,
    private val queue: VersionDiffQueue,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun queueComparisonsMadePossibleBy(extracted: DocumentTextExtracted) {
        val queued = diffs.pairsAround(extracted.versionId).count(queue::queueComparison)

        if (queued > 0) log.debug("Queued {} comparison(s) around version {}", queued, extracted.versionId)
    }
}
