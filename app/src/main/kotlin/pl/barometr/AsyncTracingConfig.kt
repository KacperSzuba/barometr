package pl.barometr

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.task.TaskDecorator
import org.springframework.core.task.support.ContextPropagatingTaskDecorator

/**
 * Keeps the trace when work moves to another thread.
 *
 * Every hop in this system that matters is a hop between threads. A module listener
 * runs after the transaction that published its event commits, on a pool thread; a job
 * runs when a worker gets to it. Without this, each of those starts a trace of its own
 * and "follow this document from the fetch to the alert" becomes four unrelated traces
 * that cannot be joined afterwards — the timestamps overlap and the ids share nothing.
 *
 * The queue's gap is wider and is closed separately, by carrying the context in the job
 * row: a thread-local decorator cannot help across minutes and machines.
 */
@Configuration
class AsyncTracingConfig {

    /**
     * Boot applies a single `TaskDecorator` bean to the executor it builds for `@Async`,
     * which is the executor Spring Modulith's listeners run on.
     */
    @Bean
    fun contextPropagatingTaskDecorator(): TaskDecorator = ContextPropagatingTaskDecorator()
}
