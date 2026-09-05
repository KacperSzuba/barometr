package pl.barometr.corpus.internal.diff

import pl.barometr.corpus.api.DocumentVersionId

/**
 * A queued comparison as the handler receives it: two identities and nothing else.
 *
 * The texts are resolved when the job runs rather than carried in the payload, because
 * a payload that carried hashes would be asserting, hours later, something the archive
 * is the authority on.
 */
data class RequestedComparison(
    val fromVersionId: DocumentVersionId,
    val toVersionId: DocumentVersionId,
)
