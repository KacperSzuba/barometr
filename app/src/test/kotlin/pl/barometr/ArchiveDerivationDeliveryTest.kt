package pl.barometr

import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.ingestion.api.ExternalId
import pl.barometr.ingestion.api.PayloadKind
import pl.barometr.ingestion.api.RawPayload
import pl.barometr.ingestion.internal.RawDocumentArchiver
import pl.barometr.shared.Ids
import pl.barometr.sources.api.ConnectorId
import pl.barometr.sources.api.SourceRegistry
import pl.barometr.testing.PostgresTestDatabase
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * That archiving a document really does derive one, in the assembled application.
 *
 * This is the seam every derived fact in the system hangs from, and the one nothing
 * else can see. The module tests compose each step by hand and call it directly, which
 * says nothing about whether the steps are connected: whether Spring Modulith can
 * write the publication, serialise the event, deliver it and mark it complete — and,
 * the part that actually broke, whether the event is published inside a transaction at
 * all. An `@ApplicationModuleListener` runs after commit, so published outside a
 * transaction it is recorded and then never delivered. The application ingested eight
 * thousand documents, registered eight thousand publications, derived nothing, and
 * logged nothing about it.
 *
 * It drives the archiver rather than publishing the event itself, which is exactly the
 * difference that matters: an earlier version of this test published inside a
 * transaction of its own and passed against code that had none. Reaching into
 * ingestion's internals is the price — a connector receives its sink from the runtime,
 * so there is no published way in — and the alternative, driving a real connector
 * against a live source, is not a test.
 */
@SpringBootTest
@ResourceLock(PostgresTestDatabase.APPLICATION_LOCK)
class ArchiveDerivationDeliveryTest {

    @Autowired
    private lateinit var archiver: RawDocumentArchiver

    @Autowired
    private lateinit var sources: SourceRegistry

    // The application's database, not one of this class's own: what is being
    // asserted is what the running application wrote.
    private val dsl = PostgresTestDatabase.applicationDsl()

    @Test
    fun `an archived payload becomes a document without anything else being called`() {
        val sejm = assertNotNull(
            sources.byConnector(ConnectorId("sejm")),
            "the Sejm source is registry data, seeded by a migration",
        )
        val address = ExternalId("term10/print/${Ids.next()}")

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

        val document = assertNotNull(
            awaitDocument(address),
            "the payload was archived but never derived: the event was published, " +
                "and either no transaction committed it or nothing delivered it",
        )
        assertEquals(DocumentKind("print").value, document)
    }

    /**
     * Polled rather than awaited on a latch: delivery is asynchronous, and the only
     * thing observable from outside is the row it leaves behind.
     */
    private fun awaitDocument(address: ExternalId): String? {
        val deadline = System.nanoTime() + WAIT.toNanos()
        var kind: String? = null

        while (System.nanoTime() < deadline && kind == null) {
            kind = dsl.select(KIND)
                .from(DOCUMENTS)
                .where(EXTERNAL_ID.eq(address.value))
                .fetchOne(KIND)
            if (kind == null) Thread.sleep(POLL.toMillis())
        }

        return kind
    }

    companion object {
        /**
         * Named rather than typed: corpus owns this table and generates no code for
         * anyone else, which is the boundary working as intended.
         */
        private val DOCUMENTS = DSL.table(DSL.name("corpus", "document"))
        private val EXTERNAL_ID = DSL.field(DSL.name("external_id"), String::class.java)
        private val KIND = DSL.field(DSL.name("kind"), String::class.java)

        private val WAIT: Duration = Duration.ofSeconds(20)
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
