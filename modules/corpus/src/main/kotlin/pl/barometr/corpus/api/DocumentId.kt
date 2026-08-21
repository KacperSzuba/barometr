package pl.barometr.corpus.api

import java.util.UUID

/** The logical document: one row of `corpus.document`, stable across its versions. */
@JvmInline
value class DocumentId(val value: UUID) {
    override fun toString(): String = value.toString()
}
