package pl.barometr.corpus.api

import java.util.UUID

/**
 * One version of a document — the thing every derived claim in the system cites.
 *
 * A summary sentence, a stage transition and an act reference all point at a
 * (version, char_start, char_end) triple, which is what makes provenance verifiable
 * rather than asserted.
 */
@JvmInline
value class DocumentVersionId(val value: UUID) {
    override fun toString(): String = value.toString()
}
