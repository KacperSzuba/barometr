package pl.barometr.alerts.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.alerts.internal.jooq.tables.references.SUPPRESSED_ADDRESS
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Addresses nothing is ever sent to again. SQL only.
 */
@Repository
class SuppressionRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Adds the address, or updates why it is there.
     *
     * A bounce after an unsubscribe is still worth recording: both stop the mail, and
     * the later reason is the one support will be asked about.
     */
    fun suppress(address: String, reason: SuppressionReason, detail: String? = null) {
        val now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

        dsl.insertInto(SUPPRESSED_ADDRESS)
            .set(SUPPRESSED_ADDRESS.ADDRESS, normalize(address))
            .set(SUPPRESSED_ADDRESS.REASON, reason.wireName)
            .set(SUPPRESSED_ADDRESS.DETAIL, detail)
            .set(SUPPRESSED_ADDRESS.SUPPRESSED_AT, now)
            .onConflict(SUPPRESSED_ADDRESS.ADDRESS)
            .doUpdate()
            .set(SUPPRESSED_ADDRESS.REASON, reason.wireName)
            .set(SUPPRESSED_ADDRESS.DETAIL, detail)
            .set(SUPPRESSED_ADDRESS.SUPPRESSED_AT, now)
            .execute()
    }

    fun suppresses(address: String): Boolean =
        dsl.fetchExists(
            dsl.selectFrom(SUPPRESSED_ADDRESS)
                .where(SUPPRESSED_ADDRESS.ADDRESS.eq(normalize(address))),
        )

    fun reasonFor(address: String): SuppressionReason? =
        dsl.select(SUPPRESSED_ADDRESS.REASON)
            .from(SUPPRESSED_ADDRESS)
            .where(SUPPRESSED_ADDRESS.ADDRESS.eq(normalize(address)))
            .fetchOne()
            ?.value1()
            ?.let(SuppressionReason::of)

    /**
     * Case and surrounding space are not part of an address as far as this list is
     * concerned. A provider reporting `Ewa@Example.COM` for a bounce must suppress the
     * address we hold as `ewa@example.com`, or the list quietly stops working.
     */
    private fun normalize(address: String) = address.trim().lowercase()
}
