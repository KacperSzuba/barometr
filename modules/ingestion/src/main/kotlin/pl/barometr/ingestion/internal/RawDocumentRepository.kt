package pl.barometr.ingestion.internal

import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import pl.barometr.ingestion.internal.jooq.tables.references.RAW_DOCUMENT
import pl.barometr.shared.Ids
import pl.barometr.sources.api.SourceId
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/**
 * Persistence for raw documents. SQL only — no blob writes, no events, no policy.
 *
 * Separated out because everything else about ingestion is worth testing without a
 * database, and because a repository that also published events would make the two
 * impossible to reason about independently.
 */
@Repository
class RawDocumentRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Records the document, or reports that this exact content was already known.
     *
     * The decision is the database's, not ours: it collides with the
     * `(source, external_id, content_hash)` unique index, so two connectors racing
     * on the same document cannot both win.
     *
     * @return the new identifier, or null when the content was already recorded.
     */
    fun insertIfAbsent(document: NewRawDocument): UUID? {
        val id = Ids.next()

        val inserted = dsl.insertInto(RAW_DOCUMENT)
            .set(RAW_DOCUMENT.ID, id)
            .set(RAW_DOCUMENT.SOURCE_ID, document.sourceId.value)
            .set(RAW_DOCUMENT.EXTERNAL_ID, document.externalId.value)
            .set(RAW_DOCUMENT.CONTENT_HASH, document.contentHash.bytes)
            .set(RAW_DOCUMENT.BLOB_KEY, document.blobKey)
            .set(RAW_DOCUMENT.PAYLOAD_KIND, document.payloadKind.wireName)
            .set(RAW_DOCUMENT.HTTP_ETAG, document.etag)
            .set(RAW_DOCUMENT.HTTP_LAST_MODIFIED, document.lastModified)
            .set(RAW_DOCUMENT.FETCHED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .set(RAW_DOCUMENT.RUN_ID, document.runId)
            .onConflictDoNothing()
            .execute()

        return id.takeIf { inserted > 0 }
    }

    /**
     * How many documents sit *directly* under an external-id prefix.
     *
     * External ids form a hierarchy — `term10/proceeding/41` has
     * `term10/proceeding/41/voting/60` beneath it — so a plain prefix match counts
     * descendants too. Asking for `term10/proceeding/` that way returned 974 where
     * 75 was correct, because every voting matched as well.
     *
     * Implemented with `position` on the remainder rather than a `NOT LIKE`
     * pattern, so a prefix containing `%` or `_` cannot change the meaning of the
     * query.
     */
    fun countDirectlyUnder(sourceId: SourceId, prefix: String): Int {
        val remainder = DSL.substring(RAW_DOCUMENT.EXTERNAL_ID, prefix.length + 1)

        return dsl.fetchCount(
            RAW_DOCUMENT,
            RAW_DOCUMENT.SOURCE_ID.eq(sourceId.value)
                .and(RAW_DOCUMENT.EXTERNAL_ID.startsWith(prefix))
                .and(DSL.position(remainder, "/").eq(0)),
        )
    }
}
