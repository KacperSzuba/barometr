package pl.barometr.platform

import java.time.Instant

/**
 * What a caller has left, after this request has been counted.
 *
 * The three numbers are the ones `X-RateLimit-*` carries, and they are returned even
 * when the request was refused: a client that has been turned away needs to know when to
 * come back more than one that got through.
 */
data class RateLimit(
    val allowed: Boolean,
    /** How many requests the window holds in total. */
    val limit: Int,
    val remaining: Int,
    /** When the bucket is full again — what a client waits for. */
    val resetAt: Instant,
)
