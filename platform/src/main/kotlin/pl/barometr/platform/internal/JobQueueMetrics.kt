package pl.barometr.platform.internal

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import pl.barometr.platform.internal.jooq.tables.references.JOB

/**
 * How much work is waiting, and how much has given up.
 *
 * The queue already reports how long a job took and how often one failed. What was
 * missing is the number that says whether the system is keeping up at all: a backlog
 * that grows is the first symptom of every ingestion problem there is, and it is
 * invisible in a timer — jobs that never ran took no time.
 *
 * The dead letter is here for the opposite reason. It never shrinks on its own, so a
 * count that moves at all is somebody's afternoon.
 *
 * Gauges over the table rather than counters kept in memory: the question is how many
 * are in each state, which survives a restart. A counter would reset to zero and report
 * an empty queue.
 */
@Component
class JobQueueMetrics(private val dsl: DSLContext) : MeterBinder {

    override fun bindTo(meters: MeterRegistry) {
        WATCHED.forEach { status ->
            Gauge.builder("jobs.queue.depth") { countOf(status) }
                .tag("status", status)
                .description("Jobs in the queue by what became of them")
                .register(meters)
        }
    }

    private fun countOf(status: String): Double =
        dsl.fetchCount(JOB, JOB.STATUS.eq(status)).toDouble()

    private companion object {
        /**
         * Waiting, running, and given up on. The two terminal-and-fine states are left
         * out: a count of everything that ever succeeded is a number nobody watches, and
         * it grows without bound.
         */
        val WATCHED = listOf("pending", "running", "dead")
    }
}
