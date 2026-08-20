package pl.barometr.ingestion.api

/**
 * The source declined to serve a resource — robots.txt, or a reservation against
 * text and data mining.
 *
 * Distinct from a failure: retrying will not help, and it is for the caller to
 * decide whether the run can continue without that resource. A refusal on one
 * document is a gap worth recording; a refusal on the listing that names the
 * documents is the run.
 *
 * Declared in the SPI rather than per connector, because every connector faces the
 * same two situations and the runtime should not have to learn a second vocabulary
 * for each source.
 */
class SourceAccessDeniedException(val resource: String, val reason: String) :
    RuntimeException("Access denied to $resource: $reason")
