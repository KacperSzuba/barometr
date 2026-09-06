package pl.barometr.taxonomy.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.taxonomy.internal.jooq.tables.references.CLASSIFICATION_PROGRESS
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** Where the walk over the archive got to. SQL only. */
@Repository
class ClassificationProgressRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Nothing recorded reads as "not started", which is the right answer for a lexicon
     * version nobody has walked with yet — including the one that has just replaced it.
     */
    fun progressOf(lexiconVersion: String, kind: String): ClassificationProgress =
        dsl.selectFrom(CLASSIFICATION_PROGRESS)
            .where(CLASSIFICATION_PROGRESS.LEXICON_VERSION.eq(lexiconVersion))
            .and(CLASSIFICATION_PROGRESS.SUBJECT_KIND.eq(kind))
            .fetchOne { ClassificationProgress(it.lastSubjectId, it.completedAt != null) }
            ?: ClassificationProgress(lastSubjectId = null, completed = false)

    /**
     * The walk got this far. Two statements rather than one that decides what to write:
     * a position and a completion mean different things, and an upsert that carried
     * both would have to say "keep whichever I did not bring", which is a conditional
     * in SQL for the sake of one fewer method.
     */
    fun recordPosition(lexiconVersion: String, kind: String, lastSubjectId: UUID) {
        val now = now()

        dsl.insertInto(CLASSIFICATION_PROGRESS)
            .set(CLASSIFICATION_PROGRESS.LEXICON_VERSION, lexiconVersion)
            .set(CLASSIFICATION_PROGRESS.SUBJECT_KIND, kind)
            .set(CLASSIFICATION_PROGRESS.LAST_SUBJECT_ID, lastSubjectId)
            .set(CLASSIFICATION_PROGRESS.UPDATED_AT, now)
            .onConflict(CLASSIFICATION_PROGRESS.LEXICON_VERSION, CLASSIFICATION_PROGRESS.SUBJECT_KIND)
            .doUpdate()
            .set(CLASSIFICATION_PROGRESS.LAST_SUBJECT_ID, lastSubjectId)
            .set(CLASSIFICATION_PROGRESS.UPDATED_AT, now)
            .execute()
    }

    /**
     * The walk ran out of archive. Everything stored from now on arrives through the
     * listener, so there is nothing left for a walk to find until the lexicon changes.
     *
     * The position it reached is left as it was: what the walk got through stays
     * readable after it has finished.
     */
    fun recordCompletion(lexiconVersion: String, kind: String) {
        val now = now()

        dsl.insertInto(CLASSIFICATION_PROGRESS)
            .set(CLASSIFICATION_PROGRESS.LEXICON_VERSION, lexiconVersion)
            .set(CLASSIFICATION_PROGRESS.SUBJECT_KIND, kind)
            .set(CLASSIFICATION_PROGRESS.COMPLETED_AT, now)
            .set(CLASSIFICATION_PROGRESS.UPDATED_AT, now)
            .onConflict(CLASSIFICATION_PROGRESS.LEXICON_VERSION, CLASSIFICATION_PROGRESS.SUBJECT_KIND)
            .doUpdate()
            .set(CLASSIFICATION_PROGRESS.COMPLETED_AT, now)
            .set(CLASSIFICATION_PROGRESS.UPDATED_AT, now)
            .execute()
    }

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
