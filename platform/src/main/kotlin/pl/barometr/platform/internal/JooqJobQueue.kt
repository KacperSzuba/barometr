package pl.barometr.platform.internal

import org.jooq.DSLContext
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobQueue
import pl.barometr.platform.JobType
import pl.barometr.platform.NewJob
import pl.barometr.platform.internal.jooq.tables.references.JOB
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class JooqJobQueue(
    private val dsl: DSLContext,
    private val backoff: JobBackoffPolicy,
    private val tracing: JobTracing,
    private val clock: Clock,
) : JobQueue {

    private fun now(): OffsetDateTime = clock.instant().atOffset(ZoneOffset.UTC)

    override fun enqueue(job: NewJob): Boolean {
        val now = now()

        val inserted = dsl.insertInto(JOB)
            .set(JOB.ID, Ids.next())
            .set(JOB.TYPE, job.type.value)
            .set(JOB.PAYLOAD, JSONB.valueOf(job.payload))
            .set(JOB.STATUS, PENDING)
            .set(JOB.PRIORITY, job.priority.level.toShort())
            .set(JOB.ATTEMPTS, 0)
            .set(JOB.MAX_ATTEMPTS, job.maxAttempts)
            .set(JOB.RUN_AFTER, job.runAfter?.atOffset(ZoneOffset.UTC) ?: now)
            .set(JOB.DEDUP_KEY, job.dedupKey)
            // Whoever queued this, so the work can be read as part of what they were
            // doing rather than as a trace that begins nowhere.
            .set(JOB.TRACE_CONTEXT, tracing.currentContext())
            .set(JOB.CREATED_AT, now)
            .set(JOB.UPDATED_AT, now)
            // Collides only with the partial unique index on `dedup_key`, which
            // covers pending and running rows. Deduplication is therefore the
            // database's decision, not a read-then-write race between producers.
            .onConflictDoNothing()
            .execute()

        return inserted > 0
    }

    /**
     * `UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)`.
     *
     * The subquery locks only the rows it returns and steps over rows another
     * worker already holds, so N workers poll the same table without contending
     * and without ever seeing the same job twice.
     */
    @Transactional
    override fun claim(worker: String, limit: Int): List<ClaimedJob> {
        val now = now()

        val claimable = dsl.select(JOB.ID)
            .from(JOB)
            .where(JOB.STATUS.eq(PENDING))
            .and(JOB.RUN_AFTER.le(now))
            .orderBy(JOB.PRIORITY.asc(), JOB.RUN_AFTER.asc())
            .limit(limit)
            .forUpdate()
            .skipLocked()

        return dsl.update(JOB)
            .set(JOB.STATUS, RUNNING)
            .set(JOB.LOCKED_BY, worker)
            .set(JOB.LOCKED_AT, now)
            .set(JOB.ATTEMPTS, JOB.ATTEMPTS.plus(1))
            .set(JOB.UPDATED_AT, now)
            .where(JOB.ID.`in`(claimable))
            .returningResult(
                JOB.ID, JOB.TYPE, JOB.PAYLOAD, JOB.ATTEMPTS, JOB.MAX_ATTEMPTS, JOB.TRACE_CONTEXT,
            )
            .fetch()
            .map { record ->
                ClaimedJob(
                    id = record[JOB.ID]!!,
                    type = JobType(record[JOB.TYPE]!!),
                    payload = record[JOB.PAYLOAD]?.data() ?: "{}",
                    attempt = record[JOB.ATTEMPTS]!!,
                    maxAttempts = record[JOB.MAX_ATTEMPTS]!!,
                    traceContext = record[JOB.TRACE_CONTEXT],
                )
            }
    }

    override fun succeed(jobId: UUID) {
        val now = now()
        dsl.update(JOB)
            .set(JOB.STATUS, SUCCEEDED)
            // Clearing the lock is not tidiness: `ck_job_lock_consistent` requires
            // that only a running row holds one.
            .setNull(JOB.LOCKED_BY)
            .setNull(JOB.LOCKED_AT)
            .set(JOB.UPDATED_AT, now)
            .where(JOB.ID.eq(jobId))
            .execute()
    }

    @Transactional
    override fun fail(jobId: UUID, error: String) {
        val state = dsl.select(JOB.ATTEMPTS, JOB.MAX_ATTEMPTS)
            .from(JOB)
            .where(JOB.ID.eq(jobId))
            .fetchOne() ?: return

        val attempts = state[JOB.ATTEMPTS] ?: 0
        val maxAttempts = state[JOB.MAX_ATTEMPTS] ?: NewJob.DEFAULT_MAX_ATTEMPTS
        val exhausted = attempts >= maxAttempts
        val now = now()

        dsl.update(JOB)
            .set(JOB.STATUS, if (exhausted) DEAD else PENDING)
            .set(JOB.RUN_AFTER, if (exhausted) now else now.plus(backoff.delayAfter(attempts)))
            .setNull(JOB.LOCKED_BY)
            .setNull(JOB.LOCKED_AT)
            .set(JOB.LAST_ERROR, error.take(MAX_ERROR_LENGTH))
            .set(JOB.UPDATED_AT, now)
            .where(JOB.ID.eq(jobId))
            .execute()
    }

    override fun reclaimAbandoned(olderThan: Instant): Int {
        val now = now()
        return dsl.update(JOB)
            .set(JOB.STATUS, PENDING)
            .setNull(JOB.LOCKED_BY)
            .setNull(JOB.LOCKED_AT)
            .set(JOB.UPDATED_AT, now)
            .where(JOB.STATUS.eq(RUNNING))
            .and(JOB.LOCKED_AT.lt(olderThan.atOffset(ZoneOffset.UTC)))
            .execute()
    }

    private companion object {
        const val PENDING = "pending"
        const val RUNNING = "running"
        const val SUCCEEDED = "succeeded"
        const val DEAD = "dead"

        const val MAX_ERROR_LENGTH = 4000
    }
}
