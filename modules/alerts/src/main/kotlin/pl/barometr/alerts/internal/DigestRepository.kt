package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.alerts.internal.jooq.tables.references.DIGEST
import pl.barometr.identity.api.UserId
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Closed windows. SQL only.
 */
@Repository
class DigestRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    fun open(owner: UserId): Digest {
        val digest = Digest(Ids.next(), clock.instant())

        dsl.insertInto(DIGEST)
            .set(DIGEST.ID, digest.id)
            .set(DIGEST.OWNER_ID, owner.value)
            .set(DIGEST.CREATED_AT, OffsetDateTime.ofInstant(digest.createdAt, ZoneOffset.UTC))
            .execute()

        return digest
    }

    /** When this person last had one, which is what says whether a boundary has passed. */
    fun lastFor(owner: UserId): Instant? =
        dsl.select(DIGEST.CREATED_AT)
            .from(DIGEST)
            .where(DIGEST.OWNER_ID.eq(owner.value))
            .orderBy(DIGEST.CREATED_AT.desc())
            .limit(1)
            .fetchOne()
            ?.value1()
            ?.toInstant()

    fun byId(id: UUID): Digest? =
        dsl.selectFrom(DIGEST)
            .where(DIGEST.ID.eq(id))
            .fetchOne { Digest(it.id!!, it.createdAt!!.toInstant()) }

    /** Who it was closed for — the digest itself does not carry it, the table does. */
    fun ownerOf(id: UUID): UserId? =
        dsl.select(DIGEST.OWNER_ID)
            .from(DIGEST)
            .where(DIGEST.ID.eq(id))
            .fetchOne()
            ?.value1()
            ?.let(::UserId)

    fun listFor(owner: UserId, limit: Int): List<Digest> =
        dsl.selectFrom(DIGEST)
            .where(DIGEST.OWNER_ID.eq(owner.value))
            .orderBy(DIGEST.CREATED_AT.desc())
            .limit(limit)
            .fetch { Digest(it.id!!, it.createdAt!!.toInstant()) }
}
