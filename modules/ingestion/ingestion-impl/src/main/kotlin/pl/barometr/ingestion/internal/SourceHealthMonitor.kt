package pl.barometr.ingestion.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.RunReport
import pl.barometr.sources.api.SourceDefinition
import pl.barometr.sources.api.SourceRuns
import java.util.Locale

/**
 * Watches what a finished run brought back and decides whether the source looks
 * broken.
 *
 * Its own class because "did this run look healthy" is a different question from
 * "did this run happen", and mixing the two left the runner unable to explain
 * either. Everything observational lives here: the counters and the judgement.
 */
@Component
class SourceHealthMonitor(
    private val runs: SourceRuns,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * [sourceUnchanged] is the connector's own statement that the source reported
     * nothing new, which no count can express: a healthy poll of an idle source
     * legitimately archives zero documents.
     */
    fun reviewCompletedRun(
        source: SourceDefinition,
        mode: IngestionMode,
        report: RunReport,
        sourceUnchanged: Boolean,
    ) {
        recordVolume(source, mode, report)
        reviewVolume(source, mode, report, sourceUnchanged)
    }

    private fun recordVolume(source: SourceDefinition, mode: IngestionMode, report: RunReport) {
        val tags = arrayOf("source", source.connectorId.value, "mode", mode.wireName)
        meters.counter("ingestion.documents.seen", *tags).increment(report.documentsSeen.toDouble())
        meters.counter("ingestion.documents.stored", *tags).increment(report.documentsStored.toDouble())
    }

    /**
     * The failure this system is most likely to suffer: a source answering HTTP 200
     * with nothing at all. Nothing throws, no status is wrong, and without a
     * baseline it is indistinguishable from a quiet day — so a finished run is
     * compared against the ones before it.
     */
    private fun reviewVolume(
        source: SourceDefinition,
        mode: IngestionMode,
        report: RunReport,
        sourceUnchanged: Boolean,
    ) {
        // A source that reported no change fetched nothing by design. Treating that
        // as an anomaly would raise an alert every quarter of an hour.
        if (sourceUnchanged) return

        val expectedMinimum = source.expectedMinRecordsPerRun
        if (expectedMinimum != null && report.documentsSeen < expectedMinimum) {
            flag(source, mode, "saw ${report.documentsSeen}, expected at least $expectedMinimum")
            return
        }

        val average = runs.recentAverageDocumentsSeen(source.id, mode, ANOMALY_WINDOW) ?: return
        if (average >= 1.0 && report.documentsSeen < average * ANOMALY_FRACTION) {
            flag(source, mode, "saw ${report.documentsSeen}, recent average " + String.format(Locale.ROOT, "%.1f", average))
        }
    }

    private fun flag(source: SourceDefinition, mode: IngestionMode, detail: String) {
        log.warn("Volume anomaly for source {} ({}): {}", source.connectorId, mode, detail)
        meters.counter(
            "ingestion.volume.anomaly",
            "source", source.connectorId.value,
            "mode", mode.wireName,
        ).increment()
    }

    private companion object {
        const val ANOMALY_WINDOW = 10

        /** Below a fifth of the recent average counts as an outage, not a slow day. */
        const val ANOMALY_FRACTION = 0.2
    }
}
