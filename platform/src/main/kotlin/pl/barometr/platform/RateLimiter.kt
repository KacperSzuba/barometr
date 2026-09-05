package pl.barometr.platform

import java.time.Duration

/**
 * A token bucket per caller, shared across instances.
 *
 * A limiter held in a process is a limiter per replica: two instances mean twice the
 * limit, and an autoscaler means whatever it decides that afternoon. This one lives in
 * the database, which is the piece of infrastructure this system already has — the trade
 * against Redis is written down in the migration.
 *
 * One call consumes one token and reports what is left, refused or not: a client that has
 * been turned away needs the numbers more than one that got through.
 */
interface RateLimiter {

    /**
     * @param bucket who is being limited — `key:<id>`, `ip:<address>`.
     * @param limit how many requests the window holds.
     * @param window how long a full bucket takes to refill from empty.
     */
    fun consume(bucket: String, limit: Int, window: Duration): RateLimit

    /** Removes buckets nobody has touched for [idleFor]; a full bucket is not worth a row. */
    fun forgetIdleBuckets(idleFor: Duration): Int
}
