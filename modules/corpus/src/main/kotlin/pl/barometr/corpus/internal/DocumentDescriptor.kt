package pl.barometr.corpus.internal

import pl.barometr.corpus.api.DocumentKind
import java.time.Instant

/**
 * What an archived payload turns out to be.
 *
 * Three facts, and no more: everything else a document says is read out of the
 * archive by whoever needs it, at the time they need it. Widening this type is how a
 * derivation step turns into a second, competing model of every source.
 */
data class DocumentDescriptor(
    val kind: DocumentKind,
    /** Null where the source's format has no title to give. */
    val title: String?,
    /** When the source says the document was issued, not when we fetched it. */
    val publishedAt: Instant?,
)
