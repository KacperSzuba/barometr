package pl.barometr.audit.internal

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.barometr.audit.api.AuditOutcome
import pl.barometr.audit.api.AuditableAttempt
import pl.barometr.audit.internal.jooq.tables.references.AUDIT_EVENT
import pl.barometr.identity.api.UserId
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The trail itself. SQL only.
 */
@Repository
class AuditEventRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Appends one entry, chained to the last.
     *
     * **In a transaction of its own**, which is what `REQUIRES_NEW` is here for: the
     * whole reason to record a denial is that the thing being recorded went wrong, and
     * an entry that rolled back along with the request it describes would be missing
     * from exactly the cases this log is kept for.
     *
     * **And in an explicit one**, which is what `transactionResult` is for. The lock
     * below is transaction-scoped, so without a transaction it is released the instant
     * the statement that takes it returns — and the chain forks. Relying on an
     * annotation for that would mean the guarantee quietly disappears wherever this
     * object is constructed rather than injected, which is every test.
     *
     * Serialising appends costs a lock held for one insert, a handful of times per
     * request. That is a price worth paying for evidence that means something.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun append(attempt: AuditableAttempt): AuditEntry = dsl.transactionResult { transaction ->
        val write = DSL.using(transaction)

        // Transaction-scoped, so it is released by the commit below and by nothing
        // else. Two appends reading the same last hash would write two entries
        // claiming the same predecessor, and a chain that forks is not a chain.
        write.execute("SELECT pg_advisory_xact_lock({0})", DSL.value(CHAIN_LOCK))

        val at = clock.instant()
        val previous = lastHash(write)
        val hash = AuditHash.of(previous, at, attempt)

        val sequence = write.insertInto(AUDIT_EVENT)
            .set(AUDIT_EVENT.AT, OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .set(AUDIT_EVENT.ACTOR_ID, attempt.actor?.value)
            .set(AUDIT_EVENT.ACTOR_LABEL, attempt.actorLabel)
            .set(AUDIT_EVENT.ACTION, attempt.action)
            .set(AUDIT_EVENT.RESOURCE, attempt.resource)
            .set(AUDIT_EVENT.OUTCOME, attempt.outcome.wireName)
            .set(AUDIT_EVENT.STATUS, attempt.status)
            .set(AUDIT_EVENT.PEER, attempt.peer)
            .set(AUDIT_EVENT.DETAIL, attempt.detail)
            .set(AUDIT_EVENT.PREVIOUS_HASH, previous)
            .set(AUDIT_EVENT.HASH, hash)
            .returning(AUDIT_EVENT.SEQUENCE)
            .fetchOne()!!
            .sequence!!

        AuditEntry(
            sequence = sequence,
            at = at,
            actor = attempt.actor,
            actorLabel = attempt.actorLabel,
            action = attempt.action,
            resource = attempt.resource,
            outcome = attempt.outcome,
            status = attempt.status,
            peer = attempt.peer,
            detail = attempt.detail,
            hash = hash,
            previousHash = previous,
        )
    }

    /** One account's own history, newest first. */
    fun historyOf(actor: UserId, limit: Int): List<AuditEntry> =
        read(AUDIT_EVENT.ACTOR_ID.eq(actor.value), limit)

    /** Everything after [after], oldest first — the order the chain was written in. */
    fun inChainOrder(after: Long, limit: Int): List<AuditEntry> =
        dsl.selectFrom(AUDIT_EVENT)
            .where(AUDIT_EVENT.SEQUENCE.gt(after))
            .orderBy(AUDIT_EVENT.SEQUENCE)
            .limit(limit)
            .fetch(::toEntry)

    private fun lastHash(write: DSLContext): String? =
        write.select(AUDIT_EVENT.HASH)
            .from(AUDIT_EVENT)
            .orderBy(AUDIT_EVENT.SEQUENCE.desc())
            .limit(1)
            .fetchOne()
            ?.value1()

    private fun read(condition: Condition, limit: Int): List<AuditEntry> =
        dsl.selectFrom(AUDIT_EVENT)
            .where(condition)
            .orderBy(AUDIT_EVENT.SEQUENCE.desc())
            .limit(limit)
            .fetch(::toEntry)

    private fun toEntry(row: Record) = AuditEntry(
        sequence = row[AUDIT_EVENT.SEQUENCE]!!,
        at = row[AUDIT_EVENT.AT]!!.toInstant(),
        actor = row[AUDIT_EVENT.ACTOR_ID]?.let(::UserId),
        actorLabel = row[AUDIT_EVENT.ACTOR_LABEL],
        action = row[AUDIT_EVENT.ACTION]!!,
        resource = row[AUDIT_EVENT.RESOURCE]!!,
        outcome = AuditOutcome.of(row[AUDIT_EVENT.OUTCOME]!!)
            ?: error("stored outcome '${row[AUDIT_EVENT.OUTCOME]}'"),
        status = row[AUDIT_EVENT.STATUS],
        peer = row[AUDIT_EVENT.PEER],
        detail = row[AUDIT_EVENT.DETAIL],
        hash = row[AUDIT_EVENT.HASH]!!,
        previousHash = row[AUDIT_EVENT.PREVIOUS_HASH],
    )

    private companion object {
        /**
         * One number, chosen once, meaning "the audit chain". Advisory locks share a
         * namespace across the database, so agreeing on a constant is the whole
         * protocol.
         */
        const val CHAIN_LOCK = 4_155_444_954_764_111L
    }
}
