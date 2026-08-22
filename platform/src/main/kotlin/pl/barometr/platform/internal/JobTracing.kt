package pl.barometr.platform.internal

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.Span
import io.micrometer.tracing.Tracer
import io.micrometer.tracing.propagation.Propagator
import org.springframework.stereotype.Component

/**
 * Carries a trace across the gap between queueing a job and running it.
 *
 * A queue is where a trace normally ends. The request that enqueued the work returns,
 * its span closes, and minutes later another thread — possibly on another machine —
 * does the work under a trace of its own. "Follow this document from the fetch to the
 * alert" then becomes three unrelated traces and a guess.
 *
 * The context travels as a string, in the format the propagator chooses, and this class
 * never looks inside it. That is what keeps the queue's schema out of the tracing
 * library's business: a W3C `traceparent` today, whatever replaces it later, and the
 * column does not change either way.
 *
 * **Both dependencies are optional**, because tracing is assembled in the application
 * and the queue is tested without it. Nullable rather than `ObjectProvider`: it is how
 * Spring is told a dependency is optional in Kotlin, and it leaves this class something
 * a test can construct in one line. With nothing injected it records nothing and
 * continues nothing, which is what an untraced build should do.
 */
@Component
class JobTracing(
    private val tracer: Tracer?,
    private val propagator: Propagator?,
    private val observations: ObservationRegistry?,
) {

    /** The caller's trace, as something a text column can hold. Null when untraced. */
    fun currentContext(): String? {
        val current = tracer?.currentTraceContext()?.context() ?: return null
        val carrier = mutableMapOf<String, String>()

        propagator?.inject(current, carrier) { fields, key, value ->
            if (fields != null && key != null && value != null) fields[key] = value
        } ?: return null

        return carrier.entries.joinToString(SEPARATOR) { "${it.key}=${it.value}" }
    }

    /**
     * Runs [work] as a continuation of [context] rather than as a trace of its own.
     *
     * The span is named for the job type, so a trace reads as what happened rather than
     * as a list of thread names: the request, then the fetch it queued, then the
     * derivation that fetch announced.
     */
    fun <T> continuing(context: String?, span: String, work: () -> T): T {
        val tracer = tracer ?: return work()

        // Two spans, and they are not the same moment. This one is the worker picking
        // the job up — the link back to whoever queued it — and the observation inside
        // is the work. A trace reads: the request, the claim, the ingestion.
        val claimed = builderFor(context, tracer).name(CLAIMED).start()

        return tracer.withSpan(claimed).use {
            try {
                observed(span, work)
            } catch (failure: Exception) {
                // On the span rather than only in the log: a trace showing a job that
                // simply stopped is the one nobody can act on. Rethrown, because it is
                // the queue that decides whether to retry.
                claimed.error(failure)
                throw failure
            } finally {
                claimed.end()
            }
        }
    }

    /**
     * The work itself, inside an observation rather than only inside the span above.
     *
     * Not ceremony: what carries a trace onto another thread is the observation, not the
     * span. A job that archives a document publishes an event, a module listener picks
     * it up after the commit on a pool thread, and with only a span in scope that
     * listener starts a trace of its own — which is the hop this whole exercise exists
     * to keep intact.
     */
    private fun <T> observed(name: String, work: () -> T): T {
        val registry = observations ?: return work()

        return Observation.createNotStarted(name, registry).observe(work)!!
    }

    /**
     * The builder the propagator hands back already carries the remote parent, so the
     * job's span hangs directly off the request that queued it.
     *
     * Extracting the context and then starting a span *from* it would put a third span
     * in between — real, empty, and parent to the work — which is a trace that reads
     * as if something happened that did not.
     */
    private fun builderFor(context: String?, tracer: Tracer): Span.Builder {
        if (context == null || propagator == null) return tracer.spanBuilder()

        val carrier = context.split(SEPARATOR)
            .mapNotNull { field ->
                field.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
            }
            .toMap()

        return propagator.extract(carrier) { fields, key -> fields[key] }
    }

    private companion object {
        /** The moment a worker took the job, as distinct from doing it. */
        const val CLAIMED = "job.claimed"


        /**
         * A newline: the propagator's fields are header values, which cannot contain
         * one, and a comma would collide with `tracestate`'s own separator.
         */
        const val SEPARATOR = "\n"
    }
}
