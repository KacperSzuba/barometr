package pl.barometr.legislative.internal

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.internal.jooq.tables.references.ACT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_CONTINUATION
import pl.barometr.legislative.internal.jooq.tables.references.DRAFT_IDENTIFIER
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Drafts. SQL only.
 *
 * A draft has no natural key of its own — it is `UD383` in RPL and `term10/print/424`
 * in the Sejm, months apart — so finding one goes through [DraftIdentifierRepository]
 * and this holds only what a draft *is*, in the vocabulary of [DraftFromRegister]
 * that both registers translate into.
 */
@Repository
class DraftRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    fun insertDraft(draft: DraftFromRegister): DraftId {
        val id = Ids.next()

        dsl.insertInto(DRAFT)
            .set(DRAFT.ID, id)
            .set(DRAFT.TITLE, draft.title)
            .set(DRAFT.TITLE_NORMALISED, ActTitles.normalise(draft.title))
            .set(DRAFT.INITIATOR, draft.initiator.wireName)
            .set(DRAFT.TERM, draft.term)
            .set(DRAFT.STARTED_ON, draft.startedOn)
            .set(DRAFT.CLOSED_ON, draft.closedOn)
            .set(DRAFT.OUTCOME, draft.outcome?.wireName)
            .set(DRAFT.CREATED_AT, now())
            .set(DRAFT.UPDATED_AT, now())
            .execute()

        return DraftId(id)
    }

    /**
     * The register's current description of a draft it already knows.
     *
     * A restatement, not a history: how the draft got here is in `stage_transition`,
     * which is append-only precisely so this row can be overwritten without losing
     * anything.
     */
    fun restateDraft(id: DraftId, draft: DraftFromRegister) {
        dsl.update(DRAFT)
            .set(DRAFT.TITLE, draft.title)
            .set(DRAFT.TITLE_NORMALISED, ActTitles.normalise(draft.title))
            .set(DRAFT.INITIATOR, draft.initiator.wireName)
            .set(DRAFT.STARTED_ON, draft.startedOn)
            .set(DRAFT.CLOSED_ON, draft.closedOn)
            .set(DRAFT.OUTCOME, draft.outcome?.wireName)
            .set(DRAFT.UPDATED_AT, now())
            .where(DRAFT.ID.eq(id.value))
            .execute()
    }

    /**
     * The draft RPL knows by this project id, if its card has never been read for the
     * stages it puts out to comment.
     *
     * Null covers two different answers on purpose — no such draft, or one already
     * read — because the caller does the same thing with both: walk on. It is the
     * question a sweep over the archived cards asks of every card it finds, so it is
     * one indexed lookup rather than two.
     */
    @Transactional(readOnly = true)
    fun draftAwaitingConsultationsFromCard(projectId: String): DraftId? =
        dsl.select(DRAFT.ID)
            .from(DRAFT)
            .join(DRAFT_IDENTIFIER).on(DRAFT_IDENTIFIER.DRAFT_ID.eq(DRAFT.ID))
            .where(DRAFT_IDENTIFIER.SCHEME.eq(DraftIdentifierScheme.RCL_PROJECT.wireName))
            .and(DRAFT_IDENTIFIER.VALUE.eq(projectId))
            .and(DRAFT.CONSULTATIONS_READ_AT.isNull)
            .fetchOne { DraftId(it.value1()!!) }

    /**
     * Records that this draft's card has been read for consultation stages.
     *
     * Written by the projector as well as the sweep: a card just projected has been
     * read, and leaving it unmarked would have the sweep fetch and parse it again to
     * reach the answer it already has.
     */
    fun markConsultationsReadFromCard(id: DraftId) {
        dsl.update(DRAFT)
            .set(DRAFT.CONSULTATIONS_READ_AT, now())
            .where(DRAFT.ID.eq(id.value))
            .execute()
    }

    /** Set once the draft has been published and the act it became is known. */
    fun linkToAct(id: DraftId, actId: ActId) {
        dsl.update(DRAFT)
            .set(DRAFT.ACT_ID, actId.value)
            .set(DRAFT.UPDATED_AT, now())
            .where(DRAFT.ID.eq(id.value))
            .and(DRAFT.ACT_ID.isNull)
            .execute()
    }

    /**
     * A draft with the one hard date in the picture: the day the act it became starts
     * applying, joined from the act rather than copied onto the draft, so it cannot go
     * stale against the register that states it.
     */
    @Transactional(readOnly = true)
    fun summaryOf(id: DraftId): DraftSummary? =
        summaries().where(DRAFT.ID.eq(id.value)).fetchOne(::toSummary)

    /** Every draft, oldest first, for the read model to be rebuilt from. */
    @Transactional(readOnly = true)
    fun allSummaries(): List<DraftSummary> = summaries().orderBy(DRAFT.CREATED_AT).fetch(::toSummary)

    private fun summaries() = dsl.select(
        DRAFT.ID,
        DRAFT.TITLE,
        DRAFT.INITIATOR,
        DRAFT.TERM,
        DRAFT.STARTED_ON,
        DRAFT.CLOSED_ON,
        DRAFT.OUTCOME,
        ACT.IN_FORCE_FROM,
    )
        .from(DRAFT)
        .leftJoin(ACT).on(ACT.ID.eq(DRAFT.ACT_ID))

    private fun toSummary(record: Record) = DraftSummary(
        id = DraftId(record[DRAFT.ID]!!),
        title = record[DRAFT.TITLE]!!,
        initiator = DraftInitiator.entries.firstOrNull { it.wireName == record[DRAFT.INITIATOR] }
            ?: DraftInitiator.UNKNOWN,
        term = record[DRAFT.TERM],
        startedOn = record[DRAFT.STARTED_ON],
        closedOn = record[DRAFT.CLOSED_ON],
        outcome = DraftOutcome.entries.firstOrNull { it.wireName == record[DRAFT.OUTCOME] },
        inForceFrom = record[ACT.IN_FORCE_FROM],
    )

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

    /**
     * The draft this act was, if the identity matching ever tied the two together.
     *
     * One draft becomes one act, so the column is on the draft and this reads it
     * backwards — which is the direction a reader travels: they are looking at the law
     * and want to know how it got there.
     */
    fun draftBecoming(act: ActId): DraftId? =
        dsl.select(DRAFT.ID)
            .from(DRAFT)
            .where(DRAFT.ACT_ID.eq(act.value))
            .orderBy(DRAFT.CREATED_AT)
            .limit(1)
            .fetchOne()
            ?.value1()
            ?.let(::DraftId)

    /**
     * What a draft is called, when its register says it began, and every number it is
     * quoted by — the whole of what deciding a join needs.
     *
     * Two queries rather than one with an aggregate: a draft carries a handful of
     * identifiers, and joining them onto the row would return the title once per
     * number for the sake of saving a round trip nobody is counting.
     */
    @Transactional(readOnly = true)
    fun identityOf(id: DraftId): DraftIdentity? {
        val draft = dsl.select(DRAFT.TITLE, DRAFT.TITLE_NORMALISED, DRAFT.STARTED_ON)
            .from(DRAFT)
            .where(DRAFT.ID.eq(id.value))
            .fetchOne() ?: return null

        val identifiers = dsl.select(DRAFT_IDENTIFIER.SCHEME, DRAFT_IDENTIFIER.VALUE)
            .from(DRAFT_IDENTIFIER)
            .where(DRAFT_IDENTIFIER.DRAFT_ID.eq(id.value))
            .fetch { record ->
                DraftIdentifierScheme.entries
                    .firstOrNull { it.wireName == record.value1() }
                    ?.let { DraftIdentifierValue(it, record.value2()!!) }
            }
            .filterNotNull()

        return DraftIdentity(
            id = id,
            title = draft.value1()!!,
            normalisedTitle = draft.value2()!!,
            startedOn = draft.value3(),
            identifiers = identifiers,
        )
    }

    /**
     * A draft in the other register quoting one of these numbers, and not already
     * joined to something.
     *
     * This is the join both registers make possible without anyone guessing: the
     * Sejm's register prints the Council of Ministers' number, and a card that carries
     * the same number under its own scheme is the same draft. It stays a search rather
     * than a lookup because which scheme the counterpart files a number under is the
     * other register's business, not ours.
     */
    @Transactional(readOnly = true)
    fun unjoinedDraftQuoting(numbers: Collection<String>, register: DraftRegister, excluding: DraftId): DraftId? {
        if (numbers.isEmpty()) return null

        return dsl.select(DRAFT.ID)
            .from(DRAFT)
            .where(claimedBy(register))
            .and(DRAFT.ID.ne(excluding.value))
            .and(notJoined(register))
            .and(
                DSL.exists(
                    DSL.selectOne()
                        .from(DRAFT_IDENTIFIER)
                        .where(DRAFT_IDENTIFIER.DRAFT_ID.eq(DRAFT.ID))
                        .and(DRAFT_IDENTIFIER.VALUE.`in`(numbers)),
                ),
            )
            // A number quoted by two drafts in one register is a mistake somewhere, and
            // taking the older one keeps this deterministic rather than pretending the
            // ambiguity is not there. It is counted where it is decided, not here.
            .orderBy(DRAFT.CREATED_AT)
            .limit(1)
            .fetchOne { DraftId(it.value1()!!) }
    }

    /**
     * The closest title in the other register, among drafts nothing has been joined to
     * yet and whose own start is on the right side of this one's.
     *
     * That last bound is what keeps the fallback honest. Ministries file a dozen
     * near-identically titled amendments of the same act over a decade, and without it
     * the nearest title is regularly the one from three years ago: a government draft
     * cannot have started after the print it became, and a print cannot have started
     * before the draft it came from. A draft whose register never stated a start is
     * still considered — an unknown date is not evidence against a join.
     */
    @Transactional(readOnly = true)
    fun closestUnjoinedByTitle(
        normalisedTitle: String,
        register: DraftRegister,
        atLeast: Double,
        startedNoLaterThan: LocalDate? = null,
        startedNoEarlierThan: LocalDate? = null,
        excluding: DraftId,
    ): DraftTitleMatch? {
        // `similarity()` is pg_trgm's and `%` is what lets the GIN index narrow the
        // search before it is computed; neither has a jOOQ DSL equivalent, so the
        // operator is a template with bound values — never concatenated text. `%`
        // applies Postgres's own threshold of 0.3 first, which is why [atLeast] is
        // documented as needing to stay above it.
        val similarity = DSL.field(
            "similarity({0}, {1})",
            Double::class.java,
            DRAFT.TITLE_NORMALISED,
            DSL.value(normalisedTitle),
        )
        val indexable = DSL.condition("{0} % {1}", DRAFT.TITLE_NORMALISED, DSL.value(normalisedTitle))

        return dsl.select(DRAFT.ID, DRAFT.TITLE, similarity)
            .from(DRAFT)
            .where(claimedBy(register))
            .and(DRAFT.ID.ne(excluding.value))
            .and(notJoined(register))
            .and(indexable)
            .and(similarity.ge(atLeast))
            .and(startedNoLaterThan?.let { DRAFT.STARTED_ON.isNull.or(DRAFT.STARTED_ON.le(it)) } ?: DSL.noCondition())
            .and(startedNoEarlierThan?.let { DRAFT.STARTED_ON.isNull.or(DRAFT.STARTED_ON.ge(it)) } ?: DSL.noCondition())
            .orderBy(similarity.desc())
            .limit(1)
            .fetchOne { record ->
                DraftTitleMatch(
                    draftId = DraftId(record.value1()!!),
                    title = record.value2()!!,
                    similarity = record.value3()!!,
                )
            }
    }

    /** A draft belongs to the register whose key it was claimed under. */
    private fun claimedBy(register: DraftRegister): Condition = DSL.exists(
        DSL.selectOne()
            .from(DRAFT_IDENTIFIER)
            .where(DRAFT_IDENTIFIER.DRAFT_ID.eq(DRAFT.ID))
            .and(DRAFT_IDENTIFIER.SCHEME.eq(register.claimedBy.wireName)),
    )

    /**
     * Nothing has been joined to this draft yet, checked against the column its own
     * register occupies: a government draft is spent once something is its print, and
     * a print once something became it.
     */
    private fun notJoined(register: DraftRegister): Condition = DSL.notExists(
        DSL.selectOne()
            .from(DRAFT_CONTINUATION)
            .where(
                when (register) {
                    DraftRegister.GOVERNMENT -> DRAFT_CONTINUATION.GOVERNMENT_DRAFT_ID.eq(DRAFT.ID)
                    DraftRegister.SEJM -> DRAFT_CONTINUATION.SEJM_DRAFT_ID.eq(DRAFT.ID)
                },
            ),
    )
}
