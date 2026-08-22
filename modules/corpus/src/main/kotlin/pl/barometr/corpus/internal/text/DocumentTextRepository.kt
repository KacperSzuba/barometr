package pl.barometr.corpus.internal.text

import org.jooq.DSLContext
import org.springframework.stereotype.Repository
import pl.barometr.corpus.api.DocumentVersionId
import pl.barometr.corpus.internal.jooq.tables.references.DOCUMENT_CHUNK
import pl.barometr.corpus.internal.jooq.tables.references.DOCUMENT_VERSION
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * A version's text and the chunks cut from it. SQL only — no blob reads, no parsing,
 * no events.
 */
@Repository
class DocumentTextRepository(
    private val dsl: DSLContext,
    private val clock: Clock,
) {

    /**
     * Records the text, or reports that another delivery recorded it first.
     *
     * `WHERE extracted_at IS NULL` is what makes redelivery free, and it is a claim
     * rather than a check: the update either takes the row or finds it already taken,
     * with no gap between reading and writing for a second delivery to fall into. The
     * chunks are then inserted only by whoever won, so they cannot be doubled.
     *
     * Both statements run inside the listener's transaction, so a failure between them
     * leaves the version unextracted rather than extracted-with-no-chunks — and the
     * event register hands it back.
     *
     * @return false when the version already had its text, which is the normal outcome
     *   of a redelivery and nothing to report.
     */
    fun recordExtractedText(
        versionId: DocumentVersionId,
        textHash: ContentHash,
        textLength: Int,
        chunks: List<TextChunk>,
    ): Boolean {
        val claimed = dsl.update(DOCUMENT_VERSION)
            .set(DOCUMENT_VERSION.TEXT_HASH, textHash.bytes)
            .set(DOCUMENT_VERSION.TEXT_LENGTH, textLength)
            .set(DOCUMENT_VERSION.EXTRACTED_AT, now())
            .where(DOCUMENT_VERSION.ID.eq(versionId.value))
            .and(DOCUMENT_VERSION.EXTRACTED_AT.isNull)
            .execute()

        if (claimed == 0) return false

        dsl.batch(
            chunks.map { chunk ->
                dsl.insertInto(DOCUMENT_CHUNK)
                    .set(DOCUMENT_CHUNK.ID, Ids.next())
                    .set(DOCUMENT_CHUNK.DOCUMENT_VERSION_ID, versionId.value)
                    .set(DOCUMENT_CHUNK.ORDINAL, chunk.ordinal)
                    .set(DOCUMENT_CHUNK.CHAR_START, chunk.charStart)
                    .set(DOCUMENT_CHUNK.CHAR_END, chunk.charEnd)
                    .set(DOCUMENT_CHUNK.CONTENT, chunk.content)
            },
        ).execute()

        return true
    }

    private fun now() = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
}
