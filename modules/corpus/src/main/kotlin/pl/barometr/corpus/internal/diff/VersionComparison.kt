package pl.barometr.corpus.internal.diff

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.ChangeKind
import pl.barometr.corpus.api.DocumentVersionsCompared
import pl.barometr.corpus.api.UnitChange
import pl.barometr.corpus.api.VersionDiff
import pl.barometr.corpus.api.VersionDiffId
import pl.barometr.corpus.internal.structure.EditorialUnitReader
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import java.time.Clock

/**
 * Compares two versions of one document and records what changed.
 *
 * The order is the argument. Both texts are read out of the derived bucket — the same
 * characters every offset in the result indexes, and never the source PDF. Each is
 * read into editorial units, because a bill is a tree of numbered things and comparing
 * it as a wall of text answers a question nobody asked. The units are then paired by
 * [UnitAlignment], which is where a renumbering stops being three hundred pages of
 * deletions. Only what is left — the units that genuinely say something different — is
 * looked at word by word.
 *
 * **Recording is idempotent and the database decides.** A retried job, a redelivered
 * event and a sweep can all arrive at the same pair; the unique index on the pair and
 * the reading admits one of them. Nothing is announced by the loser, because nothing
 * changed.
 */
@Service
class VersionComparison(
    private val blobs: BlobStore,
    private val reader: EditorialUnitReader,
    private val alignment: UnitAlignment,
    private val words: WordLevelChanges,
    private val diffs: VersionDiffRepository,
    private val events: ApplicationEventPublisher,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** @return what was recorded, or null when the pair was already compared or has no text to compare. */
    fun compareVersions(pair: ComparablePair): VersionDiff? {
        val before = textOf(pair.fromTextHash) ?: return unreadable(pair)
        val after = textOf(pair.toTextHash) ?: return unreadable(pair)

        val changes = changesBetween(before, after)
        val diff = diffOf(pair, changes)

        if (!diffs.recordComparison(diff, changes)) {
            log.debug("Versions {} and {} were already compared", pair.fromVersionId, pair.toVersionId)
            return null
        }

        meters.counter("corpus.diff.recorded").increment()
        log.info(
            "Compared {} with {}: {} changes, {} substantive",
            pair.fromVersionId,
            pair.toVersionId,
            diff.changeCount,
            diff.substantiveChanges,
        )

        events.publishEvent(
            DocumentVersionsCompared(
                documentId = pair.documentId,
                diffId = diff.id,
                fromVersionId = pair.fromVersionId,
                toVersionId = pair.toVersionId,
                substantiveChanges = diff.substantiveChanges,
                occurredAt = diff.computedAt,
            ),
        )

        return diff
    }

    /** The changes between two texts, in the order a reader walks the newer one. */
    fun changesBetween(before: String, after: String): List<UnitChange> {
        val older = reader.unitsIn(before).map { UnitReading.of(before, it) }
        val newer = reader.unitsIn(after).map { UnitReading.of(after, it) }

        return alignment.alignedTo(older, newer).map(::changeOf)
    }

    private fun changeOf(aligned: AlignedUnits): UnitChange {
        val inside = wordsInside(aligned)
        val unit = (aligned.after ?: aligned.before)?.unit
            ?: error("an alignment about neither unit")

        return UnitChange(
            kind = aligned.kind,
            unitKind = unit.kind.wireName,
            substantive = aligned.substantive,
            fromPath = aligned.before?.path,
            fromCharStart = aligned.before?.unit?.charStart,
            fromCharEnd = aligned.before?.unit?.charEnd,
            toPath = aligned.after?.path,
            toCharStart = aligned.after?.unit?.charStart,
            toCharEnd = aligned.after?.unit?.charEnd,
            similarity = aligned.similarity,
            words = inside.changes,
            wordsTruncated = inside.truncated,
        )
    }

    /**
     * Word-level detail for a modified unit and nothing else.
     *
     * An added or removed unit changed entirely, and its range says so; a moved one
     * reads identically in its new place, which is what made it a move. Running the
     * word diff on either would produce a highlight over the whole unit and call it
     * information.
     */
    private fun wordsInside(aligned: AlignedUnits): WordChanges =
        if (aligned.kind == ChangeKind.MODIFIED && aligned.before != null && aligned.after != null) {
            words.changesWithin(aligned.before, aligned.after)
        } else {
            WordChanges(emptyList(), truncated = false)
        }

    private fun diffOf(pair: ComparablePair, changes: List<UnitChange>) = VersionDiff(
        id = VersionDiffId(Ids.next()),
        documentId = pair.documentId,
        fromVersionId = pair.fromVersionId,
        toVersionId = pair.toVersionId,
        readerVersion = READER_VERSION,
        unitsAdded = changes.count { it.kind == ChangeKind.ADDED },
        unitsRemoved = changes.count { it.kind == ChangeKind.REMOVED },
        unitsModified = changes.count { it.kind == ChangeKind.MODIFIED },
        unitsMoved = changes.count { it.kind == ChangeKind.MOVED },
        substantiveChanges = changes.count(UnitChange::substantive),
        computedAt = clock.instant(),
    )

    private fun textOf(hash: ContentHash): String? =
        blobs.read(BlobBucket.DERIVED, hash)?.use { it.readBytes().toString(Charsets.UTF_8) }

    /**
     * A version whose text the archive claims to hold and the store does not.
     *
     * Counted rather than thrown: the bytes are not going to appear on the fourth
     * retry, and a dead letter here would say "comparison failed" about a version whose
     * text is simply gone. Re-extraction is what fixes it, and the counter is what says
     * how often that is needed.
     */
    private fun unreadable(pair: ComparablePair): VersionDiff? {
        meters.counter("corpus.diff.skipped", "reason", "text-missing").increment()
        log.warn("No stored text for one of {} and {}", pair.fromVersionId, pair.toVersionId)
        return null
    }

    companion object {
        /**
         * Which reading produced a stored comparison: this parser, this alignment.
         *
         * Bumped when either changes in a way that would give a different answer, which
         * makes every pair eligible for comparison again beside what is already
         * recorded. Nothing is rewritten — a reader who followed a link to a change
         * still sees the change that was there.
         */
        const val READER_VERSION = 1
    }
}
