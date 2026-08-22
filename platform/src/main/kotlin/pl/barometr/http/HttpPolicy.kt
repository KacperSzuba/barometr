package pl.barometr.http

import java.time.Duration

data class HttpPolicy(
    /** Enforced per host by a token bucket, not left to each connector. */
    val requestsPerSecond: Double,
    val userAgent: String = DEFAULT_USER_AGENT,
    val maxAttempts: Int = 4,
    val connectTimeout: Duration = Duration.ofSeconds(10),
    val requestTimeout: Duration = Duration.ofSeconds(60),
    val robots: RobotsPolicy = RobotsPolicy.Respect,

    /**
     * The largest body this client will download.
     *
     * The whole body is buffered in memory — that is what makes content addressing
     * possible, since a hash needs all the bytes — so without a ceiling one
     * mislabelled link is an out-of-memory error in a process holding the archive's
     * only writer. It mattered little while every source served HTML pages; it
     * matters from the moment a connector follows a link to a file.
     *
     * **Only what the source declares is checked**, against `Content-Length` before
     * the body is read. A response that declines to state its length is downloaded
     * whichever size it turns out to be, because refusing it afterwards would cost
     * the memory the check exists to protect and buy nothing back. Every source read
     * here declares one.
     */
    val maxBodyBytes: Long = DEFAULT_MAX_BODY_BYTES,
) {
    init {
        require(maxBodyBytes > 0) { "A body limit must be positive, got $maxBodyBytes" }
    }

    companion object {
        const val DEFAULT_USER_AGENT = "BarometrBot/1.0 (+https://barometr.pl/bot)"

        /**
         * Sixty-four megabytes: comfortably above the largest thing these sources
         * publish — a scanned three-hundred-page bill runs to tens of megabytes —
         * and far below anything that would trouble the heap.
         */
        const val DEFAULT_MAX_BODY_BYTES = 64L * 1024 * 1024
    }
}
