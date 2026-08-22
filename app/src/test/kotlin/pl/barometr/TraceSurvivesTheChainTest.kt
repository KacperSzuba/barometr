package pl.barometr

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import io.micrometer.tracing.Tracer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pl.barometr.corpus.api.DocumentVersionRecorded
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.ingestion.api.PayloadKind
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.internal.RawDocumentArchiver
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceRegistry
import pl.barometr.testing.PostgresTestDatabase
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The specification's acceptance criterion: one document, followed from where it was
 * archived to what it became, by trace id.
 *
 * The hop being proved here is the one that would otherwise break it silently. A module
 * listener runs after the publishing transaction commits, on another thread, and
 * without the decorator that carries the context it would start a trace of its own —
 * leaving two traces that share nothing but a timestamp, which cannot be joined
 * afterwards.
 *
 * The other gap, between queueing a job and running it, is minutes and machines wide
 * and is closed by carrying the context in the job row; that is proved where it lives,
 * in `JobTracingTest`.
 */
@SpringBootTest
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class TraceSurvivesTheChainTest {

    @Autowired
    private lateinit var archiver: RawDocumentArchiver

    @Autowired
    private lateinit var sources: SourceRegistry

    @Autowired
    private lateinit var tracer: Tracer

    @Autowired
    private lateinit var observations: ObservationRegistry

    @Autowired
    private lateinit var derivations: TracedDerivations

    @Test
    fun `what the archive announces is derived under the trace that archived it`() {
        val sejm = assertNotNull(sources.byConnector(ConnectorId("sejm")))
        val address = ExternalId("term10/print/${Ids.next()}")

        // An observation rather than a bare span, because that is what the running
        // application is doing at this point: Spring wraps every request, every job and
        // every outgoing call in one, and the trace is a consequence of it.
        val traceId = Observation.createNotStarted("ingest.run", observations).observe<String> {
            archiver.archive(
                sourceId = sejm.id,
                runId = null,
                payload = RawPayload(
                    externalId = address,
                    payload = """{"number":"9999","title":"Ustawa probna","documentDate":"2026-08-12"}"""
                        .toByteArray(),
                    kind = PayloadKind.JSON,
                ),
            )
            tracer.currentTraceContext().context()!!.traceId()
        }!!

        val derivedUnder = derivations.awaitTrace()

        assertNotNull(derivedUnder, "the derivation never ran")
        assertEquals(
            traceId,
            derivedUnder,
            "the derivation belongs to the trace that archived the document",
        )
    }

    /**
     * A listener shaped exactly like the ones the contexts register, recording the trace
     * it was called under.
     *
     * Reading it off a real context's listener would mean instrumenting production code
     * to be observable by a test. This one is a real listener on the same event, on the
     * same executor, so what it sees is what corpus sees.
     */
    @TestConfiguration
    class TracedDerivationsConfig {

        @Bean
        fun tracedDerivations(tracer: Tracer) = TracedDerivations(tracer)
    }

    // `open`, because `@ApplicationModuleListener` carries `@Transactional` and Spring
    // proxies it — the same reason every real listener's class is open by the Kotlin
    // Spring plugin, which does not apply to a class declared inside a test.
    open class TracedDerivations(private val tracer: Tracer) {
        private val seen = ConcurrentHashMap<String, String>()

        @ApplicationModuleListener
        open fun onDocumentRecorded(event: DocumentVersionRecorded) {
            seen[SEEN] = tracer.currentTraceContext().context()?.traceId() ?: NONE
        }

        /**
         * The listener fires after a commit, on another thread; this waits for it.
         *
         * `NONE` rather than null when it ran without a trace, so a failure says which
         * of the two things went wrong — the listener never firing and the context not
         * surviving are different problems with different fixes.
         */
        open fun awaitTrace(): String? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                seen[SEEN]?.let { return it }
                TimeUnit.MILLISECONDS.sleep(100)
            }
            return null
        }

        private companion object {
            const val SEEN = "trace"

            /** It ran, and there was no trace to see. */
            const val NONE = "ran under no trace"
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
