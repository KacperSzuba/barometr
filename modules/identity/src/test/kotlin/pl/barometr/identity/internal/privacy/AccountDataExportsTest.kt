package pl.barometr.identity.internal.privacy

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pl.barometr.identity.api.UserId
import pl.barometr.identity.internal.jooq.tables.references.DATA_EXPORT
import pl.barometr.identity.internal.jooq.tables.references.USERS
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobQueue
import pl.barometr.platform.NewJob
import pl.barometr.shared.ErasureReport
import pl.barometr.shared.Ids
import pl.barometr.shared.PersonalDataExtract
import pl.barometr.shared.PersonalDataStore
import pl.barometr.shared.PersonalDataTable
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.internal.FilesystemBlobStore
import pl.barometr.testing.PostgresTestDatabase
import pl.barometr.testing.TestClock
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.time.Duration
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A copy of everything: requested, assembled, downloaded once, and gone a week later.
 *
 * On a real database and a real blob store, because the guarantees are theirs: the claim
 * that makes a retried job harmless, and the sweep that has to take the file with the
 * row — a deleted row whose blob survives is an export somebody can still fetch if they
 * kept the address.
 */
class AccountDataExportsTest {

    @TempDir
    lateinit var blobRoot: Path

    private val dsl = PostgresTestDatabase.dslFor(javaClass)
    private val clock = TestClock()
    private val json = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
    private val properties = PrivacyProperties()

    private lateinit var blobs: FilesystemBlobStore
    private lateinit var repository: DataExportRepository
    private lateinit var exports: AccountDataExports
    private lateinit var queue: RecordingQueue

    private var ewa: UUID = Ids.next()
    private var marek: UUID = Ids.next()

    @BeforeEach
    fun setUp() {
        dsl.deleteFrom(USERS).execute()
        ewa = user("ewa@example.test")
        marek = user("marek@example.test")

        blobs = FilesystemBlobStore(blobRoot)
        repository = DataExportRepository(dsl)
        queue = RecordingQueue()
        exports = AccountDataExports(
            stores = listOf(FakeStore("profiles"), FakeStore("alerts")),
            exports = repository,
            blobs = blobs,
            queue = queue,
            json = json,
            properties = properties,
            meters = SimpleMeterRegistry(),
            clock = clock,
        )
    }

    @Test
    fun `a request is recorded and the work is queued`() {
        val requested = exports.requestExport(UserId(ewa))

        assertEquals(ExportStatus.REQUESTED, requested.status)
        assertEquals(clock.instant().plus(properties.exportRetention), requested.expiresAt)
        assertEquals(1, queue.jobs.size)
        assertEquals(AccountDataExports.TYPE, queue.jobs.single().type)
    }

    @Test
    fun `the file holds a section per context, in a stable order`() {
        val requested = exports.requestExport(UserId(ewa))

        exports.assembleExport(requested.id, ewa)

        val document = json.readTree(exports.readExport(UserId(ewa), requested.id).readBytes().toString(Charsets.UTF_8))
        assertEquals(ewa.toString(), document.get("account").asString())
        assertEquals(
            listOf("alerts", "profiles"),
            (0 until document.get("categories").size()).map { document.get("categories").get(it).get("category").asString() },
            "sorted, so two exports of one account can be compared",
        )
    }

    /**
     * The queue delivers at least once. A second run finds the file already stored — the
     * same bytes at the same address — and the claim on the row is what stops it being
     * recorded twice.
     */
    @Test
    fun `assembling the same export twice records it once`() {
        val requested = exports.requestExport(UserId(ewa))

        exports.assembleExport(requested.id, ewa)
        exports.assembleExport(requested.id, ewa)

        assertEquals(ExportStatus.READY, assertNotNull(repository.byId(requested.id)).status)
        assertEquals(1, dsl.fetchCount(DATA_EXPORT))
    }

    @Test
    fun `somebody else's export is not readable, and is not confirmed to exist`() {
        val requested = exports.requestExport(UserId(ewa))
        exports.assembleExport(requested.id, ewa)

        assertFailsWith<UnknownExportException> { exports.readExport(UserId(marek), requested.id) }
        assertFailsWith<UnknownExportException> { exports.exportOf(UserId(marek), requested.id) }
    }

    @Test
    fun `an export that has not been assembled cannot be read`() {
        val requested = exports.requestExport(UserId(ewa))

        assertFailsWith<UnknownExportException> { exports.readExport(UserId(ewa), requested.id) }
    }

    @Test
    fun `an expired export cannot be read, even by the account it is about`() {
        val requested = exports.requestExport(UserId(ewa))
        exports.assembleExport(requested.id, ewa)

        clock.advanceBy(properties.exportRetention.plus(Duration.ofMinutes(1)))

        assertFailsWith<UnknownExportException> { exports.readExport(UserId(ewa), requested.id) }
    }

    /** A row deleted before its blob leaves an object nothing points at and nothing cleans up. */
    @Test
    fun `retention takes the file with the row`() {
        val requested = exports.requestExport(UserId(ewa))
        exports.assembleExport(requested.id, ewa)
        val content = assertNotNull(repository.contentOf(requested.id))
        assertTrue(blobs.exists(BlobBucket.EXPORTS, content))

        clock.advanceBy(properties.exportRetention.plus(Duration.ofMinutes(1)))
        sweep()

        assertEquals(emptyList(), repository.forUser(ewa))
        assertFalse(blobs.exists(BlobBucket.EXPORTS, content), "the file goes with the row")
    }

    @Test
    fun `a failure is written down rather than left looking like a queue that stopped`() {
        val requested = exports.requestExport(UserId(ewa))

        exports.recordFailure(requested.id, "the database is on fire")

        val failed = assertNotNull(repository.byId(requested.id))
        assertEquals(ExportStatus.FAILED, failed.status)
        assertEquals("the database is on fire", failed.detail)
    }

    @Test
    fun `closing the account takes its exports with it`() {
        val requested = exports.requestExport(UserId(ewa))
        exports.assembleExport(requested.id, ewa)

        dsl.deleteFrom(USERS).where(USERS.ID.eq(ewa)).execute()

        assertEquals(0, dsl.fetchCount(DATA_EXPORT))
    }

    private fun sweep() =
        PrivacyRetentionSweep(
            exports = repository,
            credentials = pl.barometr.identity.internal.user.CredentialRetention(dsl),
            blobs = blobs,
            properties = properties,
            meters = SimpleMeterRegistry(),
            clock = clock,
        ).deleteWhatRetentionSaysToDelete()

    private fun user(email: String): UUID {
        val id = Ids.next()
        dsl.insertInto(USERS)
            .set(USERS.ID, id)
            .set(USERS.EMAIL, email)
            .set(USERS.PASSWORD_HASH, "not-a-hash")
            .set(USERS.ENABLED, true)
            .set(USERS.CREATED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .execute()
        return id
    }

    /** A context with something to hand over, as much of one as an export test needs. */
    private class FakeStore(override val category: String) : PersonalDataStore {
        override fun personalDataOf(user: UUID) = PersonalDataExtract(
            category = category,
            tables = listOf(PersonalDataTable(category, listOf(mapOf("id" to user.toString())))),
        )

        override fun erasePersonalData(user: UUID) = ErasureReport(category, emptyMap(), emptyMap())
    }

    private class RecordingQueue : JobQueue {
        val jobs = mutableListOf<NewJob>()

        override fun enqueue(job: NewJob): Boolean = jobs.add(job)

        override fun claim(worker: String, limit: Int): List<ClaimedJob> = emptyList()

        override fun succeed(jobId: UUID) = Unit

        override fun fail(jobId: UUID, error: String) = Unit

        override fun reclaimAbandoned(olderThan: java.time.Instant): Int = 0
    }
}
