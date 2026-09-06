package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import pl.barometr.corpus.api.DocumentId
import pl.barometr.legislative.internal.jooq.tables.references.ARCHIVE_WALK
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * How far a sweep has walked the archive. SQL only.
 *
 * One row per walk, written at the end of a run rather than at the end of each page: a
 * run that dies half way is a run that is repeated, and repeating a walk that only
 * upserts costs a few pages of parsing and changes nothing.
 */
@Repository
class ArchiveWalkRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /** The document the walk resumes after, or null to start at the beginning. */
    fun resume(walk: String): DocumentId? =
        dsl.select(ARCHIVE_WALK.AFTER_DOCUMENT)
            .from(ARCHIVE_WALK)
            .where(ARCHIVE_WALK.WALK.eq(walk))
            .fetchOne { it.value1()?.let(::DocumentId) }

    fun recordProgress(walk: String, after: DocumentId?) {
        dsl.insertInto(ARCHIVE_WALK)
            .set(ARCHIVE_WALK.WALK, walk)
            .set(ARCHIVE_WALK.AFTER_DOCUMENT, after?.value)
            .set(ARCHIVE_WALK.WALKED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .onConflict(ARCHIVE_WALK.WALK)
            .doUpdate()
            .set(ARCHIVE_WALK.AFTER_DOCUMENT, DSL.excluded(ARCHIVE_WALK.AFTER_DOCUMENT))
            .set(ARCHIVE_WALK.WALKED_AT, DSL.excluded(ARCHIVE_WALK.WALKED_AT))
            .execute()
    }
}
