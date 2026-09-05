package pl.barometr.identity.internal.privacy

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.jooq.tables.references.DATA_EXPORT
import pl.barometr.shared.ContentHash
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** Requests for an export, and where the file is. SQL only. */
@Repository
@Transactional(readOnly = true)
class DataExportRepository(private val dsl: DSLContext) {

    @Transactional
    fun request(export: AccountDataExport) {
        dsl.insertInto(DATA_EXPORT)
            .set(DATA_EXPORT.ID, export.id)
            .set(DATA_EXPORT.USER_ID, export.user)
            .set(DATA_EXPORT.STATUS, export.status.wireName)
            .set(DATA_EXPORT.REQUESTED_AT, at(export.requestedAt))
            .set(DATA_EXPORT.EXPIRES_AT, at(export.expiresAt))
            .execute()
    }

    /**
     * Marks an export ready, once and only from `requested`.
     *
     * The claim is the database's: a job retried after the file was stored but before the
     * row was written finds nothing to claim the second time, and does not store a second
     * copy of somebody's entire account.
     */
    @Transactional
    fun markReady(id: UUID, contentHash: ContentHash, byteSize: Long, at: Instant): Boolean =
        dsl.update(DATA_EXPORT)
            .set(DATA_EXPORT.STATUS, ExportStatus.READY.wireName)
            .set(DATA_EXPORT.CONTENT_HASH, contentHash.bytes)
            .set(DATA_EXPORT.BYTE_SIZE, byteSize)
            .set(DATA_EXPORT.COMPLETED_AT, at(at))
            .where(DATA_EXPORT.ID.eq(id))
            .and(DATA_EXPORT.STATUS.eq(ExportStatus.REQUESTED.wireName))
            .execute() > 0

    @Transactional
    fun markFailed(id: UUID, detail: String, at: Instant) {
        dsl.update(DATA_EXPORT)
            .set(DATA_EXPORT.STATUS, ExportStatus.FAILED.wireName)
            .set(DATA_EXPORT.DETAIL, detail.take(DETAIL_LENGTH))
            .set(DATA_EXPORT.COMPLETED_AT, at(at))
            .where(DATA_EXPORT.ID.eq(id))
            .execute()
    }

    fun byId(id: UUID): AccountDataExport? =
        dsl.selectFrom(DATA_EXPORT).where(DATA_EXPORT.ID.eq(id)).fetchOne(::toExport)

    /** Where the file is, for an export that is this account's, ready, and not expired. */
    fun readableContent(id: UUID, user: UUID, now: Instant): ContentHash? =
        dsl.select(DATA_EXPORT.CONTENT_HASH)
            .from(DATA_EXPORT)
            .where(DATA_EXPORT.ID.eq(id))
            .and(DATA_EXPORT.USER_ID.eq(user))
            .and(DATA_EXPORT.STATUS.eq(ExportStatus.READY.wireName))
            .and(DATA_EXPORT.EXPIRES_AT.gt(at(now)))
            .fetchOne()
            ?.value1()
            ?.let(ContentHash::ofBytes)

    fun forUser(user: UUID): List<AccountDataExport> =
        dsl.selectFrom(DATA_EXPORT)
            .where(DATA_EXPORT.USER_ID.eq(user))
            .orderBy(DATA_EXPORT.REQUESTED_AT.desc())
            .fetch(::toExport)

    /** Everything whose week is up, whatever became of it — what the sweep deletes. */
    fun expiredBefore(now: Instant, limit: Int): List<AccountDataExport> =
        dsl.selectFrom(DATA_EXPORT)
            .where(DATA_EXPORT.EXPIRES_AT.le(at(now)))
            .orderBy(DATA_EXPORT.EXPIRES_AT)
            .limit(limit)
            .fetch(::toExport)

    @Transactional
    fun delete(id: UUID): Boolean = dsl.deleteFrom(DATA_EXPORT).where(DATA_EXPORT.ID.eq(id)).execute() > 0

    /** The content hash, for a sweep that has to take the file with the row. */
    fun contentOf(id: UUID): ContentHash? =
        dsl.select(DATA_EXPORT.CONTENT_HASH)
            .from(DATA_EXPORT)
            .where(DATA_EXPORT.ID.eq(id))
            .fetchOne()
            ?.value1()
            ?.let(ContentHash::ofBytes)

    private fun toExport(record: Record) = AccountDataExport(
        id = record[DATA_EXPORT.ID]!!,
        user = record[DATA_EXPORT.USER_ID]!!,
        // A stored status this enum does not know would mean the `CHECK` and the code
        // drifted apart.
        status = ExportStatus.of(record[DATA_EXPORT.STATUS]!!) ?: error("stored export status"),
        byteSize = record[DATA_EXPORT.BYTE_SIZE],
        detail = record[DATA_EXPORT.DETAIL],
        requestedAt = record[DATA_EXPORT.REQUESTED_AT]!!.toInstant(),
        completedAt = record[DATA_EXPORT.COMPLETED_AT]?.toInstant(),
        expiresAt = record[DATA_EXPORT.EXPIRES_AT]!!.toInstant(),
    )

    private fun at(instant: Instant) = instant.atOffset(ZoneOffset.UTC)

    private companion object {
        /** Enough of a failure to act on, and not a stack trace in a column somebody reads. */
        const val DETAIL_LENGTH = 500
    }
}
