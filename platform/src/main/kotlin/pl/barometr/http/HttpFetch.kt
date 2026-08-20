package pl.barometr.http

import java.net.URI

data class HttpFetch(
    val url: URI,
    /** Previous ETag, turning this into a conditional request. */
    val etag: String? = null,
    val lastModified: String? = null,
    val headers: Map<String, String> = emptyMap(),
)
