package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.alerts.internal.jooq.tables.references.EMAIL_DELIVERY
import pl.barometr.alerts.internal.jooq.tables.references.SUPPRESSED_ADDRESS
import pl.barometr.identity.api.UserId
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * What was sent, to whom, and what happened. SQL only.
 */
@Repository
class EmailDeliveryRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Records the outcome for one digest, replacing an earlier attempt's.
     *
     * The digest is the key, so a retry updates rather than adds: what matters is
     * whether this digest went out, and a table with five rows per digest would answer
     * that question worse.
     */
    fun record(
        digest: UUID,
        owner: UserId,
        address: String,
        status: DeliveryStatus,
        detail: String? = null,
    ) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

        dsl.insertInto(EMAIL_DELIVERY)
            .set(EMAIL_DELIVERY.DIGEST_ID, digest)
            .set(EMAIL_DELIVERY.OWNER_ID, owner.value)
            .set(EMAIL_DELIVERY.ADDRESS, address)
            .set(EMAIL_DELIVERY.STATUS, status.wireName)
            .set(EMAIL_DELIVERY.DETAIL, detail)
            .set(EMAIL_DELIVERY.ATTEMPTED_AT, now)
            .onConflict(EMAIL_DELIVERY.DIGEST_ID)
            .doUpdate()
            .set(EMAIL_DELIVERY.ADDRESS, address)
            .set(EMAIL_DELIVERY.STATUS, status.wireName)
            .set(EMAIL_DELIVERY.DETAIL, detail)
            .set(EMAIL_DELIVERY.ATTEMPTED_AT, now)
            .execute()
    }

    /** Whether this digest has already gone out, which is what stops a retry resending. */
    fun wasSent(digest: UUID): Boolean =
        dsl.fetchExists(
            dsl.selectFrom(EMAIL_DELIVERY)
                .where(EMAIL_DELIVERY.DIGEST_ID.eq(digest))
                .and(EMAIL_DELIVERY.STATUS.eq(DeliveryStatus.SENT.wireName)),
        )

    fun countOf(status: DeliveryStatus): Int =
        dsl.fetchCount(EMAIL_DELIVERY, EMAIL_DELIVERY.STATUS.eq(status.wireName))

    /**
     * Suppressions live in their own table, but they are counted here because they are
     * the same question — what share of what we send arrives — and a metrics binder
     * reading two repositories would only spread that question over two files.
     */
    fun countSuppressed(reason: SuppressionReason): Int =
        dsl.fetchCount(SUPPRESSED_ADDRESS, SUPPRESSED_ADDRESS.REASON.eq(reason.wireName))

    fun statusOf(digest: UUID): DeliveryStatus? =
        dsl.select(EMAIL_DELIVERY.STATUS)
            .from(EMAIL_DELIVERY)
            .where(EMAIL_DELIVERY.DIGEST_ID.eq(digest))
            .fetchOne()
            ?.value1()
            ?.let(DeliveryStatus::of)
}
