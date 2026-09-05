package pl.barometr.identity.internal.privacy

import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.barometr.identity.internal.user.CredentialRetention
import pl.barometr.storage.BlobBucket
import pl.barometr.storage.BlobStore
import java.time.Clock

/**
 * Retention, as a thing that runs rather than a paragraph in a policy.
 *
 * Two categories, and they expire for different reasons. An export is the most
 * concentrated collection of somebody's data this system produces and stops being
 * available a week after it was made — the file goes with the row, or the deletion is
 * theatre. A revoked session or a spent token is kept for as long as somebody
 * investigating a compromise would want it and no longer.
 *
 * Locked across the deployment: two instances would delete the same rows and the second
 * would find none, which costs nothing — but the blob deletions would race, and one of
 * them would log a failure for a file the other had just removed.
 */
@Component
class PrivacyRetentionSweep(
    private val exports: DataExportRepository,
    private val credentials: CredentialRetention,
    private val blobs: BlobStore,
    private val properties: PrivacyProperties,
    private val meters: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.identity.privacy.sweep-interval:PT1H}", initialDelay = 600_000)
    @SchedulerLock(name = "identity-privacy-retention")
    @Transactional
    fun deleteWhatRetentionSaysToDelete() {
        val expired = exports.expiredBefore(clock.instant(), BATCH)
        expired.forEach { export ->
            // The file first: a row deleted before its blob leaves an object in storage
            // that nothing points at and nothing will ever clean up.
            exports.contentOf(export.id)?.let { blobs.delete(BlobBucket.EXPORTS, it) }
            exports.delete(export.id)
        }

        val credentialsGone = credentials.deleteOlderThan(clock.instant().minus(properties.credentialRetention))

        if (expired.isNotEmpty() || credentialsGone > 0) {
            meters.counter("identity.retention.deleted", "category", "export").increment(expired.size.toDouble())
            meters.counter("identity.retention.deleted", "category", "credential").increment(credentialsGone.toDouble())
            log.info("Retention: {} export(s) and {} credential row(s) deleted", expired.size, credentialsGone)
        }
    }

    private companion object {
        /** A batch per run: retention is not urgent, and a sweep that holds a transaction open is. */
        const val BATCH = 200
    }
}
