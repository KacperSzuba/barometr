package pl.barometr

import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.support.TransactionTemplate
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.ingestion.api.PayloadKind
import pl.barometr.ingestion.api.RawDocumentIngested
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.sources.api.SourceId
import pl.barometr.testing.PostgresTestDatabase
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals

/**
 * That an event published by one context actually reaches the next one, through the
 * register rather than in spite of it.
 *
 * This is the seam the whole derivation chain hangs from and the one nothing else
 * checks: the module tests compose the listener by hand and call it directly, which
 * says nothing about whether Spring Modulith can write the publication, serialise the
 * event, deliver it and mark it complete. Any of those failing leaves an application
 * that ingests happily and derives nothing — the quietest possible failure.
 *
 * The event names a source that does not exist, so the listener takes its "nothing to
 * derive" path. What is under test is the delivery, not what the listener concludes.
 */
@SpringBootTest
class ArchiveDerivationDeliveryTest {

    @Autowired
    private lateinit var events: ApplicationEventPublisher

    @Autowired
    private lateinit var transactions: TransactionTemplate

    /**
     * Read straight from the register's own table rather than through Modulith's
     * repository: what is being tested is that a row is written and completed, and
     * asking the library whether it thinks it wrote one would be asking the subject.
     * The table has no generated code here — it belongs to Modulith, not to a
     * context — so its columns are named rather than typed.
     */
    private val dsl = PostgresTestDatabase.dsl()

    @Test
    fun `an ingested document is delivered to its listener and the publication completed`() {
        val externalId = ExternalId("term10/print/${Ids.next()}")

        // Published inside a transaction because the listener runs after commit:
        // outside one, Modulith records nothing and delivers nothing.
        transactions.executeWithoutResult {
            events.publishEvent(
                RawDocumentIngested(
                    rawDocumentId = Ids.next(),
                    sourceId = SourceId(Ids.next()),
                    externalId = externalId,
                    contentHash = ContentHash.of("a payload".toByteArray()),
                    kind = PayloadKind.JSON,
                    occurredAt = Instant.parse("2026-08-21T10:00:00Z"),
                ),
            )
        }

        assertEquals(1, publicationsFor(externalId), "the publication was never registered")
        assertEquals(
            1,
            awaitCompletedPublication(externalId),
            "the publication was registered but never completed, so the listener never finished",
        )
    }

    private fun publicationsFor(externalId: ExternalId): Int =
        dsl.fetchCount(PUBLICATIONS, carrying(externalId))

    /**
     * Polled rather than awaited on a latch: delivery is asynchronous and the only
     * thing that can be observed from outside is the row it leaves behind.
     */
    private fun awaitCompletedPublication(externalId: ExternalId): Int {
        val deadline = System.nanoTime() + WAIT.toNanos()
        var completed = 0

        while (System.nanoTime() < deadline && completed == 0) {
            completed = dsl.fetchCount(PUBLICATIONS, carrying(externalId).and(COMPLETION_DATE.isNotNull))
            if (completed == 0) Thread.sleep(POLL.toMillis())
        }

        return completed
    }

    /** The stored form is JSON; the address is the one field certain to be in it. */
    private fun carrying(externalId: ExternalId) =
        SERIALIZED_EVENT.like("%" + externalId.value + "%")

    companion object {
        private val PUBLICATIONS = DSL.table(DSL.name("platform", "event_publication"))
        private val SERIALIZED_EVENT = DSL.field(DSL.name("serialized_event"), String::class.java)
        private val COMPLETION_DATE = DSL.field(DSL.name("completion_date"), OffsetDateTime::class.java)

        private val WAIT: Duration = Duration.ofSeconds(10)
        private val POLL: Duration = Duration.ofMillis(50)

        @JvmStatic
        @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { PostgresTestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { PostgresTestDatabase.username }
            registry.add("spring.datasource.password") { PostgresTestDatabase.password }
        }
    }
}
