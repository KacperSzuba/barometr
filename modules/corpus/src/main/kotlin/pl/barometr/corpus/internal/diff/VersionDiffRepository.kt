package pl.barometr.corpus.internal.diff

import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.JSONB
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import pl.barometr.corpus.api.ChangeKind
import pl.barometr.corpus.api.DocumentId
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.api.UnitChange
import pl.barometr.corpus.api.VersionDiff
import pl.barometr.corpus.api.VersionDiffId
import pl.barometr.corpus.api.WordChange
import pl.barometr.corpus.internal.jooq.tables.DocumentVersion as DocumentVersionTable
import pl.barometr.corpus.internal.jooq.tables.references.DOCUMENT_VERSION
import pl.barometr.corpus.internal.jooq.tables.references.UNIT_CHANGE
import pl.barometr.corpus.internal.jooq.tables.references.VERSION_DIFF
import pl.barometr.shared.ContentHash
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.ZoneOffset
import java.util.UUID

/**
 * Recorded comparisons and the changes in them. SQL only — no parsing, no blob reads,
 * no events.
 */
@Repository
@Transactional(readOnly = true)
class VersionDiffRepository(
    private val dsl: DSLContext,
    private val json: ObjectMapper,
) {

    /**
     * Writes a comparison, or reports that another run recorded this pair first.
     *
     * The claim is the database's, as it is for extracted text: the unique index on
     * `(from_version_id, to_version_id, reader_version)` decides, and the changes are
     * written only by whoever won. A job retried after a crash, or an event redelivered
     * by the register, therefore costs one rejected insert rather than a second copy of
     * four hundred changes.
     *
     * @return false when the pair had already been compared under this reading.
     */
    @Transactional
    fun recordComparison(diff: VersionDiff, changes: List<UnitChange>): Boolean {
        val recorded = dsl.insertInto(VERSION_DIFF)
            .set(VERSION_DIFF.ID, diff.id.value)
            .set(VERSION_DIFF.DOCUMENT_ID, diff.documentId.value)
            .set(VERSION_DIFF.FROM_VERSION_ID, diff.fromVersionId.value)
            .set(VERSION_DIFF.TO_VERSION_ID, diff.toVersionId.value)
            .set(VERSION_DIFF.READER_VERSION, diff.readerVersion)
            .set(VERSION_DIFF.UNITS_ADDED, diff.unitsAdded)
            .set(VERSION_DIFF.UNITS_REMOVED, diff.unitsRemoved)
            .set(VERSION_DIFF.UNITS_MODIFIED, diff.unitsModified)
            .set(VERSION_DIFF.UNITS_MOVED, diff.unitsMoved)
            .set(VERSION_DIFF.SUBSTANTIVE_CHANGES, diff.substantiveChanges)
            .set(VERSION_DIFF.COMPUTED_AT, diff.computedAt.atOffset(ZoneOffset.UTC))
            .onConflict(VERSION_DIFF.FROM_VERSION_ID, VERSION_DIFF.TO_VERSION_ID, VERSION_DIFF.READER_VERSION)
            .doNothing()
            .execute()

        if (recorded == 0) return false

        dsl.batch(
            changes.mapIndexed { index, change ->
                dsl.insertInto(UNIT_CHANGE)
                    .set(UNIT_CHANGE.DIFF_ID, diff.id.value)
                    // Numbered from one: the ordinal is what a reader pages by and what
                    // a link to one change carries.
                    .set(UNIT_CHANGE.ORDINAL, index + 1)
                    .set(UNIT_CHANGE.KIND, change.kind.wireName)
                    .set(UNIT_CHANGE.UNIT_KIND, change.unitKind)
                    .set(UNIT_CHANGE.SUBSTANTIVE, change.substantive)
                    .set(UNIT_CHANGE.FROM_PATH, change.fromPath)
                    .set(UNIT_CHANGE.FROM_CHAR_START, change.fromCharStart)
                    .set(UNIT_CHANGE.FROM_CHAR_END, change.fromCharEnd)
                    .set(UNIT_CHANGE.TO_PATH, change.toPath)
                    .set(UNIT_CHANGE.TO_CHAR_START, change.toCharStart)
                    .set(UNIT_CHANGE.TO_CHAR_END, change.toCharEnd)
                    .set(UNIT_CHANGE.SIMILARITY, change.similarity?.toFloat())
                    .set(UNIT_CHANGE.WORDS, change.words.takeIf { it.isNotEmpty() }?.let(::encodeWords))
                    .set(UNIT_CHANGE.WORDS_TRUNCATED, change.wordsTruncated)
            },
        ).execute()

        return true
    }

    fun comparisonOf(from: DocumentVersionId, to: DocumentVersionId, readerVersion: Int): VersionDiff? =
        dsl.selectFrom(VERSION_DIFF)
            .where(VERSION_DIFF.FROM_VERSION_ID.eq(from.value))
            .and(VERSION_DIFF.TO_VERSION_ID.eq(to.value))
            .and(VERSION_DIFF.READER_VERSION.eq(readerVersion))
            .fetchOne()
            ?.let(::toDiff)

    fun latestComparisonOf(documentId: DocumentId): VersionDiff? =
        dsl.selectFrom(VERSION_DIFF)
            .where(VERSION_DIFF.DOCUMENT_ID.eq(documentId.value))
            .orderBy(VERSION_DIFF.COMPUTED_AT.desc())
            .limit(1)
            .fetchOne()
            ?.let(::toDiff)

    fun changesIn(
        diff: VersionDiffId,
        substantiveOnly: Boolean,
        afterOrdinal: Int,
        limit: Int,
    ): List<UnitChange> =
        dsl.selectFrom(UNIT_CHANGE)
            .where(UNIT_CHANGE.DIFF_ID.eq(diff.value))
            .and(UNIT_CHANGE.ORDINAL.gt(afterOrdinal))
            .and(if (substantiveOnly) UNIT_CHANGE.SUBSTANTIVE.isTrue else DSL.noCondition())
            .orderBy(UNIT_CHANGE.ORDINAL)
            .limit(limit)
            .fetch(::toChange)

    /**
     * The pairs this version is one half of, in the order they were published.
     *
     * Two of them at most: the version and its predecessor, and the version and
     * whatever superseded it. Both are asked for, because text arrives out of order —
     * a version extracted today can complete a pair whose other half was archived
     * months ago and has been waiting for it.
     *
     * The chain is followed through `previous_version_id` rather than by arithmetic on
     * `version_no`: the column exists precisely so that a version fetched out of order
     * still knows what it superseded.
     */
    fun pairsAround(versionId: DocumentVersionId): List<ComparablePair> {
        val newer = DOCUMENT_VERSION.`as`("newer")
        val older = DOCUMENT_VERSION.`as`("older")

        return pairsWhere(newer, older, newer.ID.eq(versionId.value).or(older.ID.eq(versionId.value)))
            .fetch(::toPair)
    }

    fun pairOf(from: DocumentVersionId, to: DocumentVersionId): ComparablePair? {
        val newer = DOCUMENT_VERSION.`as`("newer")
        val older = DOCUMENT_VERSION.`as`("older")

        return pairsWhere(newer, older, newer.ID.eq(to.value).and(older.ID.eq(from.value)))
            .fetchOne(::toPair)
    }

    /** Every adjacent pair of this document's versions that has text on both sides, oldest first. */
    fun pairsOfDocument(documentId: DocumentId): List<ComparablePair> {
        val newer = DOCUMENT_VERSION.`as`("newer")
        val older = DOCUMENT_VERSION.`as`("older")

        return pairsWhere(newer, older, newer.DOCUMENT_ID.eq(documentId.value))
            .orderBy(newer.VERSION_NO)
            .fetch(::toPair)
    }

    /**
     * Pairs the archive holds that nothing has compared under this reading, oldest
     * first.
     *
     * The way back in for versions archived before this was written, and for the pairs
     * a listener missed. Paged by the newer version's identity, which is time-ordered,
     * so a sweep walks the archive without skipping what arrived while it ran.
     */
    fun pairsAwaitingComparison(
        readerVersion: Int,
        after: DocumentVersionId?,
        limit: Int,
    ): List<ComparablePair> {
        val newer = DOCUMENT_VERSION.`as`("newer")
        val older = DOCUMENT_VERSION.`as`("older")

        return pairsWhere(newer, older, after?.let { newer.ID.gt(it.value) } ?: DSL.noCondition())
            .andNotExists(
                dsl.selectOne()
                    .from(VERSION_DIFF)
                    .where(VERSION_DIFF.FROM_VERSION_ID.eq(older.ID))
                    .and(VERSION_DIFF.TO_VERSION_ID.eq(newer.ID))
                    .and(VERSION_DIFF.READER_VERSION.eq(readerVersion)),
            )
            .orderBy(newer.ID)
            .limit(limit)
            .fetch(::toPair)
    }

    /**
     * Adjacent versions that both have their text, narrowed by [narrow].
     *
     * A version with no text is not half of a pair: its payload was a scan with no
     * text layer, or its extraction has not run yet. Reporting "everything was
     * removed" about such a version would be a claim the archive itself contradicts.
     */
    private fun pairsWhere(
        newer: DocumentVersionTable,
        older: DocumentVersionTable,
        narrow: Condition,
    ) = dsl.select(newer.DOCUMENT_ID, older.ID, older.TEXT_HASH, newer.ID, newer.TEXT_HASH)
        .from(newer)
        .join(older).on(older.ID.eq(newer.PREVIOUS_VERSION_ID))
        .where(newer.TEXT_HASH.isNotNull)
        .and(older.TEXT_HASH.isNotNull)
        .and(narrow)

    /**
     * The five columns both pair queries select, in the order they select them.
     *
     * Read positionally because the two queries select from aliases of the same table —
     * `newer` and `older` — and a field looked up by name would be ambiguous between
     * them. Both text hashes are non-null by the `WHERE` that selected the row.
     */
    private fun toPair(record: Record): ComparablePair = ComparablePair(
        documentId = DocumentId(uuidAt(record, 0)),
        fromVersionId = DocumentVersionId(uuidAt(record, 1)),
        fromTextHash = ContentHash.ofBytes(hashAt(record, 2)),
        toVersionId = DocumentVersionId(uuidAt(record, 3)),
        toTextHash = ContentHash.ofBytes(hashAt(record, 4)),
    )

    private fun uuidAt(record: Record, index: Int): UUID =
        requireNotNull(record.get(index, UUID::class.java)) { "column $index of a version pair was null" }

    private fun hashAt(record: Record, index: Int): ByteArray =
        requireNotNull(record.get(index, ByteArray::class.java)) { "column $index of a version pair was null" }

    private fun toDiff(record: Record): VersionDiff = VersionDiff(
        id = VersionDiffId(record[VERSION_DIFF.ID]!!),
        documentId = DocumentId(record[VERSION_DIFF.DOCUMENT_ID]!!),
        fromVersionId = DocumentVersionId(record[VERSION_DIFF.FROM_VERSION_ID]!!),
        toVersionId = DocumentVersionId(record[VERSION_DIFF.TO_VERSION_ID]!!),
        readerVersion = record[VERSION_DIFF.READER_VERSION]!!,
        unitsAdded = record[VERSION_DIFF.UNITS_ADDED]!!,
        unitsRemoved = record[VERSION_DIFF.UNITS_REMOVED]!!,
        unitsModified = record[VERSION_DIFF.UNITS_MODIFIED]!!,
        unitsMoved = record[VERSION_DIFF.UNITS_MOVED]!!,
        substantiveChanges = record[VERSION_DIFF.SUBSTANTIVE_CHANGES]!!,
        computedAt = record[VERSION_DIFF.COMPUTED_AT]!!.toInstant(),
    )

    private fun toChange(record: Record): UnitChange = UnitChange(
        // A stored kind this enum does not know would mean the `CHECK` and the code
        // drifted apart, which is a state nothing downstream can interpret.
        kind = ChangeKind.of(record[UNIT_CHANGE.KIND]!!) ?: error("stored change kind '${record[UNIT_CHANGE.KIND]}'"),
        unitKind = record[UNIT_CHANGE.UNIT_KIND]!!,
        substantive = record[UNIT_CHANGE.SUBSTANTIVE]!!,
        fromPath = record[UNIT_CHANGE.FROM_PATH],
        fromCharStart = record[UNIT_CHANGE.FROM_CHAR_START],
        fromCharEnd = record[UNIT_CHANGE.FROM_CHAR_END],
        toPath = record[UNIT_CHANGE.TO_PATH],
        toCharStart = record[UNIT_CHANGE.TO_CHAR_START],
        toCharEnd = record[UNIT_CHANGE.TO_CHAR_END],
        similarity = record[UNIT_CHANGE.SIMILARITY]?.toDouble(),
        words = record[UNIT_CHANGE.WORDS]?.let(::decodeWords).orEmpty(),
        wordsTruncated = record[UNIT_CHANGE.WORDS_TRUNCATED]!!,
    )

    private fun encodeWords(words: List<WordChange>): JSONB =
        JSONB.valueOf(json.writeValueAsString(words.map(::toStored)))

    /**
     * Typed, not cast: a stored shape this list cannot hold has to fail here, naming
     * the column, rather than several layers away where the value is finally read.
     */
    private fun decodeWords(stored: JSONB): List<WordChange> =
        json.readValue(stored.data(), object : TypeReference<List<StoredWordChange>>() {})
            .map(::toWordChange)

    private fun toStored(change: WordChange) = StoredWordChange(
        kind = change.kind.wireName,
        fromCharStart = change.fromCharStart,
        fromCharEnd = change.fromCharEnd,
        toCharStart = change.toCharStart,
        toCharEnd = change.toCharEnd,
    )

    private fun toWordChange(stored: StoredWordChange) = WordChange(
        kind = ChangeKind.of(stored.kind) ?: error("stored word change kind '${stored.kind}'"),
        fromCharStart = stored.fromCharStart,
        fromCharEnd = stored.fromCharEnd,
        toCharStart = stored.toCharStart,
        toCharEnd = stored.toCharEnd,
    )

    /**
     * What actually sits in the `words` column.
     *
     * The kind is written as its wire name, the same vocabulary the `kind` column and
     * the `CHECK` beside it use — rather than as the enum constant's name, which would
     * make renaming a constant a silent change to data already written.
     */
    internal data class StoredWordChange(
        val kind: String,
        val fromCharStart: Int?,
        val fromCharEnd: Int?,
        val toCharStart: Int?,
        val toCharEnd: Int?,
    )
}
