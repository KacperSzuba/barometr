package pl.barometr.platform.internal

import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.handler.DefaultTracingObservationHandler
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext
import io.micrometer.tracing.otel.bridge.OtelPropagator
import io.micrometer.tracing.otel.bridge.OtelTracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gap a trace normally falls into.
 *
 * A job is queued by one request and run minutes later by another thread, possibly on
 * another machine. Unless something carries the context across that gap, "follow this
 * document from the fetch to the alert" is three unrelated traces and a guess.
 *
 * Against the real bridge and a real W3C propagator, not a stub of one: what is being
 * claimed is that a genuine `traceparent` goes into a text column and comes back out as
 * a parent, and a fake propagator would only prove that a fake propagator round-trips.
 */
class JobTracingTest {

    private val exported = InMemorySpanExporter.create()
    private val otel = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exported))
        .build()
        .get("barometr-test")

    private val tracer = OtelTracer(otel, OtelCurrentTraceContext(), {})
    private val propagator = OtelPropagator(
        ContextPropagators.create(W3CTraceContextPropagator.getInstance()),
        otel,
    )
    /**
     * A registry wired to the tracer the way Boot wires it, because the work runs inside
     * an observation and an observation with no tracing handler produces no span.
     */
    private val observations = ObservationRegistry.create().apply {
        observationConfig().observationHandler(DefaultTracingObservationHandler(tracer))
    }

    private val tracing = JobTracing(tracer, propagator, observations)

    @Test
    fun `an untraced build records nothing and still runs the work`() {
        val untraced = JobTracing(tracer = null, propagator = null, observations = null)
        var ran = false

        assertNull(untraced.currentContext(), "nothing was tracing, so there is nothing to carry")
        untraced.continuing(context = null, span = "ingest.sejm") { ran = true }

        assertTrue(ran, "work must not depend on tracing being assembled")
    }

    /** The whole point: one trace, from the request that queued the work to the work. */
    @Test
    fun `the caller's trace survives a text column and continues in the worker`() {
        val request = tracer.nextSpan().name("request").start()
        val carried = tracer.withSpan(request).use { tracing.currentContext() }
        request.end()

        val context = assertNotNull(carried, "a traced caller has something to carry")
        assertTrue(context.contains("traceparent"), context)

        tracing.continuing(context, "ingest.sejm") { }

        val claim = exported.finishedSpanItems.single { it.name == "job.claimed" }
        assertEquals(
            request.context().traceId(),
            claim.traceId,
            "the job belongs to the trace that queued it",
        )
        assertEquals(request.context().spanId(), claim.parentSpanId, "and hangs off that span")

        val work = exported.finishedSpanItems.single { it.name == "ingest.sejm" }
        assertEquals(claim.traceId, work.traceId, "and so does the work inside it")
    }

    /**
     * What carries a trace onto another thread is the observation, not the span — so a
     * job that publishes an event whose listener runs on a pool thread only keeps the
     * trace if the work ran inside one. This is that guarantee, asserted where it is
     * cheap: the work sees an observation, and it belongs to the caller's trace.
     */
    @Test
    fun `the work runs inside an observation, which is what the next thread will inherit`() {
        val request = tracer.nextSpan().name("request").start()
        val carried = tracer.withSpan(request).use { tracing.currentContext() }
        request.end()

        var observedTrace: String? = null
        tracing.continuing(carried, "ingest.sejm") {
            observedTrace = observations.currentObservation
                ?.let { tracer.currentTraceContext().context()?.traceId() }
        }

        assertEquals(
            request.context().traceId(),
            observedTrace,
            "the work runs in an observation on the caller's trace",
        )
    }

    /** A job queued before tracing existed, or by an instance that had none. */
    @Test
    fun `a job with no context still runs, under a trace of its own`() {
        tracing.continuing(context = null, span = "ingest.isap") { }

        assertTrue(exported.finishedSpanItems.any { it.name == "ingest.isap" })
    }

    /**
     * A trace that stops where a job failed says nothing about why. The failure is
     * recorded on the span and then rethrown, because it is the queue that decides
     * whether to retry — swallowing it here would turn a retryable failure into a job
     * that quietly succeeded.
     */
    @Test
    fun `a failing job marks its span and the failure still reaches the queue`() {
        val outcome = runCatching {
            tracing.continuing(context = null, span = "ingest.rcl") { error("the source refused") }
        }

        assertTrue(outcome.isFailure)
        val claim = exported.finishedSpanItems.single { it.name == "job.claimed" }
        assertTrue(claim.status.statusCode.name == "ERROR", "the span says it failed: ${claim.status}")
    }
}
