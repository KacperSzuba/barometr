package pl.barometr.ingestion.api

import org.junit.jupiter.api.Test
import pl.barometr.shared.ContentHash
import pl.barometr.shared.Ids
import pl.barometr.sources.api.SourceId
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Instant
import kotlin.test.assertEquals

/**
 * That the event survives the register it is published through.
 *
 * `@ApplicationModuleListener` is an outbox: Spring Modulith writes the event to
 * `platform.event_publication` as JSON in the publisher's transaction and reads it
 * back to deliver — or to redeliver, days later, after a listener failed. An event
 * that serialises but cannot be read back would work perfectly until the first
 * failure and then lose exactly the documents a retry existed to save.
 *
 * Worth pinning because every field here is a value class or an enum, and whether
 * those round-trip is a property of the Jackson version on the classpath rather than
 * of anything this project writes.
 */
class RawDocumentIngestedTest {

    private val json = JsonMapper.builder().addModule(kotlinModule()).build()

    @Test
    fun `survives a round trip through the event publication register`() {
        val published = RawDocumentIngested(
            rawDocumentId = Ids.next(),
            sourceId = SourceId(Ids.next()),
            externalId = ExternalId("term10/print/424"),
            contentHash = ContentHash.of("a print".toByteArray()),
            kind = PayloadKind.JSON,
            occurredAt = Instant.parse("2026-08-21T10:00:00Z"),
        )

        val delivered = json.readValue(json.writeValueAsString(published), RawDocumentIngested::class.java)

        assertEquals(published, delivered)
    }

    /**
     * Value classes are written as the value they wrap, not as an object around it.
     * Stated as a test because the stored form is a contract with rows already in the
     * register when the application is upgraded.
     */
    @Test
    fun `identifiers are stored as plain values`() {
        val hash = ContentHash.of("a print".toByteArray())
        val event = RawDocumentIngested(
            rawDocumentId = Ids.next(),
            sourceId = SourceId(Ids.next()),
            externalId = ExternalId("term10/print/424"),
            contentHash = hash,
            kind = PayloadKind.JSON,
            occurredAt = Instant.parse("2026-08-21T10:00:00Z"),
        )

        val written = json.writeValueAsString(event)

        assertEquals(true, written.contains("\"externalId\":\"term10/print/424\""), written)
        assertEquals(true, written.contains("\"contentHash\":\"${hash.hex}\""), written)
    }
}
