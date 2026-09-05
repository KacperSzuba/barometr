package pl.barometr.corpus.api

import java.util.UUID

/** One recorded comparison of two versions, under one reading. */
@JvmInline
value class VersionDiffId(val value: UUID) {
    override fun toString(): String = value.toString()
}
