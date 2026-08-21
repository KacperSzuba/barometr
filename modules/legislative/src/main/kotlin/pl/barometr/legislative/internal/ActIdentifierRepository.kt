package pl.barometr.legislative.internal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.internal.jooq.tables.references.ACT_IDENTIFIER
import java.math.BigDecimal
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * The alias table that makes "one act, three sources" work. SQL only.
 */
@Repository
class ActIdentifierRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    fun actFor(scheme: IdentifierScheme, value: String): ActId? =
        dsl.select(ACT_IDENTIFIER.ACT_ID)
            .from(ACT_IDENTIFIER)
            .where(ACT_IDENTIFIER.SCHEME.eq(scheme.wireName))
            .and(ACT_IDENTIFIER.VALUE.eq(value))
            .fetchOne { ActId(it.value1()!!) }

    /**
     * Points an identifier at an act.
     *
     * The primary key is `(scheme, value)` — the invariant that an identifier
     * resolves to exactly one act, whoever issued it — so a re-delivery updates
     * rather than duplicates. It may also *correct*: a fuzzy match written last month
     * is replaced when the act is published and states the print number itself, which
     * is the whole reason a method and a confidence are stored beside the link.
     */
    fun pointAtAct(
        scheme: IdentifierScheme,
        value: String,
        actId: ActId,
        method: MatchMethod,
        confidence: Double?,
    ) {
        dsl.insertInto(ACT_IDENTIFIER)
            .set(ACT_IDENTIFIER.ACT_ID, actId.value)
            .set(ACT_IDENTIFIER.SCHEME, scheme.wireName)
            .set(ACT_IDENTIFIER.VALUE, value)
            .set(ACT_IDENTIFIER.CONFIDENCE, confidence?.let(BigDecimal::valueOf))
            .set(ACT_IDENTIFIER.RESOLVED_BY, method.wireName)
            .set(ACT_IDENTIFIER.RESOLVED_AT, now())
            .onConflict(ACT_IDENTIFIER.SCHEME, ACT_IDENTIFIER.VALUE)
            .doUpdate()
            .set(ACT_IDENTIFIER.ACT_ID, DSL.excluded(ACT_IDENTIFIER.ACT_ID))
            .set(ACT_IDENTIFIER.CONFIDENCE, DSL.excluded(ACT_IDENTIFIER.CONFIDENCE))
            .set(ACT_IDENTIFIER.RESOLVED_BY, DSL.excluded(ACT_IDENTIFIER.RESOLVED_BY))
            .set(ACT_IDENTIFIER.RESOLVED_AT, now())
            .execute()
    }

    /** How many documents of each kind are pinned, counted by the scheme they use. */
    fun countByScheme(): Map<IdentifierScheme, Int> =
        dsl.select(ACT_IDENTIFIER.SCHEME, DSL.count())
            .from(ACT_IDENTIFIER)
            .groupBy(ACT_IDENTIFIER.SCHEME)
            .fetch()
            .mapNotNull { record ->
                IdentifierScheme.entries.firstOrNull { it.wireName == record.value1() }
                    ?.let { it to record.value2() }
            }
            .toMap()

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
