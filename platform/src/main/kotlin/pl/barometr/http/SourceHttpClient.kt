package pl.barometr.http

/**
 * The single door every connector goes through to reach the outside world.
 *
 * Rate limiting, retries with backoff, conditional requests and the robots/TDM
 * gate live here rather than in each connector — twenty connectors would
 * otherwise mean twenty subtly different retry loops, and the one that forgets
 * to honour `Retry-After` is the one that gets the whole system blocked.
 */
interface SourceHttpClient {
    fun fetch(request: HttpFetch): HttpOutcome
}
