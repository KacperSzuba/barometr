package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The aliases a draft is known by. SQL only.
 *
 * The same table as `act_identifier` one level down, for the same reason: the Sejm
 * calls a draft by its print number and RPL by the Council of Ministers' number, and
 * without this the second source to arrive creates a second draft.
 */
@Repository
class DraftIdentifierRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    fun draftFor(scheme: DraftIdentifierScheme, value: String): DraftId? =
        dsl.select(DRAFT_IDENTIFIER.DRAFT_ID)
            .from(DRAFT_IDENTIFIER)
            .where(DRAFT_IDENTIFIER.SCHEME.eq(scheme.wireName))
            .and(DRAFT_IDENTIFIER.VALUE.eq(value))
            .fetchOne { DraftId(it.value1()!!) }

    /**
     * Claims an identifier for a draft just created under it, and fails if somebody
     * else already has.
     *
     * Deliberately without a conflict clause, unlike [pointAtDraft]. Two deliveries of
     * the same process both find no draft and both create one; the primary key here is
     * what stops the second, and it has to *throw* so its whole transaction — the
     * orphan draft included — rolls back and the event is redelivered. Swallowing the
     * conflict would leave a draft nothing points at and a projector writing stages
     * against it.
     */
    fun claimForDraft(scheme: DraftIdentifierScheme, value: String, draftId: DraftId) {
        dsl.insertInto(DRAFT_IDENTIFIER)
            .set(DRAFT_IDENTIFIER.DRAFT_ID, draftId.value)
            .set(DRAFT_IDENTIFIER.SCHEME, scheme.wireName)
            .set(DRAFT_IDENTIFIER.VALUE, value)
            .set(DRAFT_IDENTIFIER.CONFIDENCE, BigDecimal.ONE)
            .set(DRAFT_IDENTIFIER.RESOLVED_BY, MatchMethod.EXACT.wireName)
            .set(DRAFT_IDENTIFIER.RESOLVED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .execute()
    }

    /**
     * Points a further alias at a draft, or leaves an existing one alone.
     *
     * `DO NOTHING` rather than an update: the primary key is the invariant that an
     * identifier resolves to exactly one draft, and a second claim on the same
     * identifier is a mistake to notice rather than a correction to apply.
     */
    fun pointAtDraft(
        scheme: DraftIdentifierScheme,
        value: String,
        draftId: DraftId,
        method: MatchMethod,
        confidence: Double?,
    ) {
        dsl.insertInto(DRAFT_IDENTIFIER)
            .set(DRAFT_IDENTIFIER.DRAFT_ID, draftId.value)
            .set(DRAFT_IDENTIFIER.SCHEME, scheme.wireName)
            .set(DRAFT_IDENTIFIER.VALUE, value)
            .set(DRAFT_IDENTIFIER.CONFIDENCE, confidence?.let(BigDecimal::valueOf))
            .set(DRAFT_IDENTIFIER.RESOLVED_BY, method.wireName)
            .set(DRAFT_IDENTIFIER.RESOLVED_AT, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
            .onConflictDoNothing()
            .execute()
    }
}
