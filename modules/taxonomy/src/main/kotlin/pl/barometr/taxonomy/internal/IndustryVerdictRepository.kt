package pl.barometr.taxonomy.internal

import org.jooq.DSLContext
import org.jooq.Record
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.shared.PkdCode
import pl.barometr.taxonomy.api.ClassifiedSubject
import pl.barometr.taxonomy.internal.jooq.tables.references.ITEM_INDUSTRY
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Recorded verdicts. SQL only — no thresholds, no policy about who may decide.
 */
@Repository
@Transactional(readOnly = true)
class IndustryVerdictRepository(private val dsl: DSLContext) {

    /**
     * Writes a verdict, replacing whatever was said about the same industry before.
     *
     * An upsert rather than an insert that can fail: a classifier re-run over the same
     * act is the normal case, and a second opinion about one code is a correction of
     * the first rather than a second row beside it.
     */
    @Transactional
    fun recordVerdict(verdict: IndustryVerdict) {
        dsl.insertInto(ITEM_INDUSTRY)
            .set(ITEM_INDUSTRY.SUBJECT_KIND, verdict.subject.kind)
            .set(ITEM_INDUSTRY.SUBJECT_ID, verdict.subject.id)
            .set(ITEM_INDUSTRY.PKD, verdict.code.value)
            .set(ITEM_INDUSTRY.STATUS, verdict.status.wireName)
            .set(ITEM_INDUSTRY.CONFIDENCE, verdict.confidence.toFloat())
            .set(ITEM_INDUSTRY.METHOD, verdict.method.wireName)
            .set(ITEM_INDUSTRY.MODEL_VERSION, verdict.modelVersion)
            .set(ITEM_INDUSTRY.DOCUMENT_VERSION_ID, verdict.citedVersion?.value)
            .set(ITEM_INDUSTRY.CHAR_START, verdict.charStart)
            .set(ITEM_INDUSTRY.CHAR_END, verdict.charEnd)
            .set(ITEM_INDUSTRY.DECIDED_AT, at(verdict.decidedAt))
            .set(ITEM_INDUSTRY.REVIEWED_AT, verdict.reviewedAt?.let(::at))
            .onConflict(ITEM_INDUSTRY.SUBJECT_KIND, ITEM_INDUSTRY.SUBJECT_ID, ITEM_INDUSTRY.PKD)
            .doUpdate()
            .set(ITEM_INDUSTRY.STATUS, verdict.status.wireName)
            .set(ITEM_INDUSTRY.CONFIDENCE, verdict.confidence.toFloat())
            .set(ITEM_INDUSTRY.METHOD, verdict.method.wireName)
            .set(ITEM_INDUSTRY.MODEL_VERSION, verdict.modelVersion)
            .set(ITEM_INDUSTRY.DOCUMENT_VERSION_ID, verdict.citedVersion?.value)
            .set(ITEM_INDUSTRY.CHAR_START, verdict.charStart)
            .set(ITEM_INDUSTRY.CHAR_END, verdict.charEnd)
            .set(ITEM_INDUSTRY.DECIDED_AT, at(verdict.decidedAt))
            .set(ITEM_INDUSTRY.REVIEWED_AT, verdict.reviewedAt?.let(::at))
            .execute()
    }

    /** The industries this subject carries, whatever anybody has said about them. */
    fun verdictsFor(subject: ClassifiedSubject): List<IndustryVerdict> =
        dsl.selectFrom(ITEM_INDUSTRY)
            .where(ITEM_INDUSTRY.SUBJECT_KIND.eq(subject.kind))
            .and(ITEM_INDUSTRY.SUBJECT_ID.eq(subject.id))
            .orderBy(ITEM_INDUSTRY.PKD)
            .fetch(::toVerdict)

    fun acceptedFor(subject: ClassifiedSubject): List<PkdCode> =
        dsl.select(ITEM_INDUSTRY.PKD)
            .from(ITEM_INDUSTRY)
            .where(ITEM_INDUSTRY.SUBJECT_KIND.eq(subject.kind))
            .and(ITEM_INDUSTRY.SUBJECT_ID.eq(subject.id))
            .and(ITEM_INDUSTRY.STATUS.eq(VerdictStatus.ACCEPTED.wireName))
            .orderBy(ITEM_INDUSTRY.PKD)
            .fetch { PkdCode(it.value1()!!) }

    /**
     * What is classified under [code] or beneath it, newest first.
     *
     * Matched on the generated digit column: `62.0` covers `62.01.Z` by level and not
     * by printed text, and this is the one place the difference is a query rather than
     * a comparison in Kotlin.
     */
    fun acceptedUnder(code: PkdCode, limit: Int): List<ClassifiedSubject> =
        dsl.select(ITEM_INDUSTRY.SUBJECT_KIND, ITEM_INDUSTRY.SUBJECT_ID)
            .from(ITEM_INDUSTRY)
            .where(ITEM_INDUSTRY.STATUS.eq(VerdictStatus.ACCEPTED.wireName))
            .and(ITEM_INDUSTRY.PKD_DIGITS.startsWith(code.value.filter(Char::isDigit)))
            .orderBy(ITEM_INDUSTRY.DECIDED_AT.desc())
            .limit(limit)
            .fetch { ClassifiedSubject(it.value1()!!, it.value2()!!) }
            .distinct()

    /** The queue: verdicts nobody was confident enough about to route on, oldest first. */
    fun pendingVerdicts(limit: Int): List<IndustryVerdict> =
        dsl.selectFrom(ITEM_INDUSTRY)
            .where(ITEM_INDUSTRY.STATUS.eq(VerdictStatus.PENDING.wireName))
            .orderBy(ITEM_INDUSTRY.DECIDED_AT)
            .limit(limit)
            .fetch(::toVerdict)

    fun countPending(): Int = dsl.fetchCount(ITEM_INDUSTRY, ITEM_INDUSTRY.STATUS.eq(VerdictStatus.PENDING.wireName))

    fun countAccepted(): Int = dsl.fetchCount(ITEM_INDUSTRY, ITEM_INDUSTRY.STATUS.eq(VerdictStatus.ACCEPTED.wireName))

    /**
     * Settles one pending verdict.
     *
     * `WHERE status = 'pending'` is the claim: two reviewers pressing at once means one
     * of them changes nothing, rather than the second silently overwriting the first.
     *
     * @return false when there was nothing pending to settle.
     */
    @Transactional
    fun settleVerdict(
        subject: ClassifiedSubject,
        code: PkdCode,
        status: VerdictStatus,
        reviewedAt: Instant,
    ): Boolean =
        dsl.update(ITEM_INDUSTRY)
            .set(ITEM_INDUSTRY.STATUS, status.wireName)
            .set(ITEM_INDUSTRY.REVIEWED_AT, at(reviewedAt))
            .where(ITEM_INDUSTRY.SUBJECT_KIND.eq(subject.kind))
            .and(ITEM_INDUSTRY.SUBJECT_ID.eq(subject.id))
            .and(ITEM_INDUSTRY.PKD.eq(code.value))
            .and(ITEM_INDUSTRY.STATUS.eq(VerdictStatus.PENDING.wireName))
            .execute() > 0

    private fun toVerdict(record: Record) = IndustryVerdict(
        subject = ClassifiedSubject(record[ITEM_INDUSTRY.SUBJECT_KIND]!!, record[ITEM_INDUSTRY.SUBJECT_ID]!!),
        code = PkdCode(record[ITEM_INDUSTRY.PKD]!!),
        // A stored value this enum does not know would mean the `CHECK` and the code
        // drifted apart, which is a state nothing downstream can interpret.
        status = VerdictStatus.of(record[ITEM_INDUSTRY.STATUS]!!) ?: error("stored status"),
        confidence = record[ITEM_INDUSTRY.CONFIDENCE]!!.toDouble(),
        method = VerdictMethod.of(record[ITEM_INDUSTRY.METHOD]!!) ?: error("stored method"),
        modelVersion = record[ITEM_INDUSTRY.MODEL_VERSION],
        citedVersion = record[ITEM_INDUSTRY.DOCUMENT_VERSION_ID]?.let(::DocumentVersionId),
        charStart = record[ITEM_INDUSTRY.CHAR_START],
        charEnd = record[ITEM_INDUSTRY.CHAR_END],
        decidedAt = record[ITEM_INDUSTRY.DECIDED_AT]!!.toInstant(),
        reviewedAt = record[ITEM_INDUSTRY.REVIEWED_AT]?.toInstant(),
    )

    private fun at(instant: Instant) = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
}
