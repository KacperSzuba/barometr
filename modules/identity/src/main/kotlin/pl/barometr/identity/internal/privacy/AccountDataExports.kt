package pl.barometr.identity.internal.privacy

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.api.UserId
import pl.barometr.platform.JobPriority
import pl.barometr.platform.JobQueue
import pl.barometr.platform.JobType
import pl.barometr.platform.NewJob
import pl.barometr.shared.Ids
import pl.barometr.shared.PersonalDataExtract
import pl.barometr.shared.PersonalDataStore
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import tools.jackson.databind.ObjectMapper
import java.io.InputStream
import java.time.Clock
import java.util.UUID

/**
 * A copy of everything, assembled from every context that holds anything.
 *
 * **Assembled in a job, not in the request.** A person with three years of alerts has a
 * file worth megabytes, and the statutory answer to "how long may this take" is a month
 * rather than a second. The request records that it was asked for — which is what the
 * deadline runs from — and the queue does the reading.
 *
 * **The same stores the erasure uses.** Anything a context can hand over it can also
 * delete, and the pairing is not decoration: a context that appears in an export and not
 * in a deletion is exactly the gap this design exists to make impossible.
 */
@Service
class AccountDataExports(
    private val stores: List<PersonalDataStore>,
    private val exports: DataExportRepository,
    private val blobs: BlobStore,
    private val queue: JobQueue,
    private val json: ObjectMapper,
    private val properties: PrivacyProperties,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Records the request and queues the work. Returns what a caller can ask about later. */
    @Transactional
    fun requestExport(user: UserId): AccountDataExport {
        val now = clock.instant()
        val export = AccountDataExport(
            id = Ids.next(),
            user = user.value,
            status = ExportStatus.REQUESTED,
            byteSize = null,
            detail = null,
            requestedAt = now,
            completedAt = null,
            expiresAt = now.plus(properties.exportRetention),
        )

        exports.request(export)
        queue.enqueue(
            NewJob(
                type = TYPE,
                payload = json.writeValueAsString(WirePayload(export.id.toString(), user.value.toString())),
                // Somebody is waiting, and the law says how long they may wait.
                priority = JobPriority.INTERACTIVE,
                dedupKey = "identity.export:${export.id}",
            ),
        )
        log.info("Data export {} requested by {}", export.id, user.value)

        return export
    }

    /**
     * Reads every context and writes one file.
     *
     * Stored in the exports bucket, which is the bucket that is allowed to forget — the
     * sweep takes both the row and the file when the week is up.
     */
    @Transactional
    fun assembleExport(id: UUID, user: UUID) {
        val document = json.writeValueAsString(
            ExportDocument(
                account = user.toString(),
                exportedAt = clock.instant().toString(),
                categories = stores.map { it.personalDataOf(user) }.sortedBy { it.category },
            ),
        )

        val stored = blobs.store(BlobBucket.EXPORTS, document.toByteArray(Charsets.UTF_8), MEDIA_TYPE)
        if (!exports.markReady(id, stored.contentHash, stored.byteSize, clock.instant())) {
            // Another run of the same job got there first; the file is the same bytes at
            // the same address, so there is nothing to undo and nothing to say.
            return
        }

        meters.summary("identity.export.bytes").record(stored.byteSize.toDouble())
        log.info("Data export {} ready: {} bytes across {} categories", id, stored.byteSize, stores.size)
    }

    @Transactional
    fun recordFailure(id: UUID, reason: String) {
        exports.markFailed(id, reason, clock.instant())
        meters.counter("identity.export.failed").increment()
    }

    @Transactional(readOnly = true)
    fun exportsOf(user: UserId): List<AccountDataExport> = exports.forUser(user.value)

    /**
     * The file, for the account it is about, while it is still theirs to read.
     *
     * Somebody else's export is reported as absent rather than forbidden: this is the
     * most concentrated collection of a person's data the system holds, and confirming
     * which identifiers exist is an answer nobody is owed.
     */
    @Transactional(readOnly = true)
    fun readExport(user: UserId, id: UUID): InputStream {
        val content = exports.readableContent(id, user.value, clock.instant()) ?: throw UnknownExportException()

        return blobs.read(BlobBucket.EXPORTS, content) ?: throw UnknownExportException()
    }

    /** What a caller may be given about somebody else's file: nothing. */
    fun exportOf(user: UserId, id: UUID): AccountDataExport =
        exports.byId(id)?.takeIf { it.user == user.value } ?: throw UnknownExportException()

    /** The job payload, converted at this boundary and nowhere else. */
    internal data class WirePayload(val exportId: String, val userId: String)

    /**
     * The file itself: who it is about, when it was made, and a section per context.
     *
     * Sorted by category so that two exports of the same account are diffable, which is
     * the first thing anybody checking this feature does.
     */
    internal data class ExportDocument(
        val account: String,
        val exportedAt: String,
        val categories: List<PersonalDataExtract>,
    )

    companion object {
        val TYPE = JobType("identity.data-export")

        const val MEDIA_TYPE = "application/json"
    }
}
