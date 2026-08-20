package pl.barometr.http

sealed interface HttpOutcome {

    /** Plain class: equality over a [ByteArray] body would compare references. */
    class Fetched(
        val body: ByteArray,
        val contentType: String?,
        val etag: String?,
        val lastModified: String?,
    ) : HttpOutcome

    /** 304. The cheapest possible answer, and the reason ETags are tracked at all. */
    data object NotModified : HttpOutcome

    data class Refused(val reason: RefusalReason, val detail: String) : HttpOutcome

    data class Failed(val statusCode: Int?, val detail: String) : HttpOutcome
}
