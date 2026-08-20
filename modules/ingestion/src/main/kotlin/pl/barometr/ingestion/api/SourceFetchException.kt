package pl.barometr.ingestion.api

/**
 * The source answered, but not with what was asked for.
 *
 * Worth retrying on a later run, which is what the queue's backoff is for — as
 * opposed to [SourceAccessDeniedException], where retrying is pointless.
 */
class SourceFetchException(val resource: String, val detail: String) :
    RuntimeException("Request to $resource failed: $detail")
