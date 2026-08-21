package pl.barometr

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pl.barometr.ingestion.api.Connector
import pl.barometr.platform.JobHandler
import pl.barometr.testing.PostgresTestDatabase
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * That the application actually starts, and starts with the parts it needs.
 *
 * The only test in the build that builds the real context. Everything else here
 * reads bytecode or composes objects by hand, which cannot notice a bean that fails
 * to construct, a `@ConfigurationProperties` class nobody scans, an entity that
 * disagrees with a migration — or, as it turned out, scheduled work that was locked
 * by an annotation with nothing behind it.
 */
@SpringBootTest
class ApplicationContextTest {

    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `every connector and job handler is registered`() {
        val connectors = context.getBeansOfType(Connector::class.java).values.map { it.id.value }
        assertTrue(connectors.containsAll(listOf("sejm", "rcl", "isap")), "registered: $connectors")

        val handlers = context.getBeansOfType(JobHandler::class.java).values
        assertEquals(
            handlers.size,
            handlers.map { it.type }.distinct().size,
            "two handlers for one job type means one of them never runs",
        )
    }

    /**
     * Scheduling is currently switched on by Boot's autoconfiguration rather than by
     * anything this project writes, which makes it exactly the kind of thing that
     * disappears in an upgrade without a word. Asserted so that it cannot.
     */
    @Test
    fun `scheduling is switched on`() {
        // Asserting on registered tasks rather than on the presence of a
        // post-processor: what matters is not that scheduling could work, but that
        // these particular methods were picked up.
        val scheduled = context.getBeansOfType(ScheduledTaskHolder::class.java).values
            .flatMap { it.scheduledTasks }

        assertTrue(
            scheduled.isNotEmpty(),
            "no @Scheduled method is registered, so nothing dispatches, polls or reaps",
        )
    }

    /**
     * `@SchedulerLock` is honoured by an interceptor that only exists when ShedLock
     * is enabled and given a lock provider. Without them the annotations are
     * decoration, and two instances dispatch the same work at the same time.
     */
    @Test
    fun `scheduled work is locked across instances`() {
        // Loaded by name: ShedLock is an implementation detail of the platform
        // module, so it is on this module's runtime classpath and not its compile
        // classpath — which is exactly as it should be.
        val lockProvider = Class.forName("net.javacrumbs.shedlock.core.LockProvider")
        assertTrue(
            context.getBeanNamesForType(lockProvider).isNotEmpty(),
            "no LockProvider: @SchedulerLock does nothing",
        )
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
