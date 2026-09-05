package pl.barometr.ingestion.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.barometr.ingestion.api.ArchiveCompleteness
import pl.barometr.ingestion.api.BackfillLauncher
import pl.barometr.ingestion.api.CompletenessReport
import pl.barometr.sources.api.ConnectorId
import java.time.LocalDate
import kotlin.math.round

/**
 * Operator endpoints for the archive: start a replay, and check whether it holds
 * everything the source says it should.
 *
 * The module owns its own routes, the way identity owns `/api/v1/auth`. Authentication
 * comes from the application's filter chain; authorisation is stated here, because
 * only this module knows what these endpoints cost. Being merely authenticated is
 * not enough: registration is open, so without the role below anyone who signs up
 * could start a multi-week crawl of somebody else's server.
 */
@RestController
@RequestMapping("/api/v1/ingestion")
@PreAuthorize("hasRole('OPERATOR')")
class BackfillController(
    private val launcher: BackfillLauncher,
    private val completeness: ArchiveCompleteness,
) {

    @PostMapping("/backfill")
    fun launch(
        @RequestParam connector: String,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
    ): BackfillResponse {
        val plan = launcher.launch(ConnectorId(connector), from, to)
        return BackfillResponse(
            connector = plan.connectorId.value,
            queued = plan.queued,
            alreadyInFlight = plan.skipped,
            partitions = plan.partitions.map { "${it.key}: ${it.label}" },
        )
    }

    @GetMapping("/completeness")
    fun completeness(@RequestParam connector: String): CompletenessResponse {
        val report = completeness.compareArchiveAgainstSource(ConnectorId(connector))
        return CompletenessResponse(
            connector = report.connectorId.value,
            complete = report.isComplete,
            tolerance = report.tolerance,
            findings = report.findings.map(::describe),
            gaps = report.gaps.map(::describe),
        )
    }

    private fun describe(finding: CompletenessReport.Finding) = FindingResponse(
        partition = finding.partition,
        kind = finding.kind,
        declared = finding.declared,
        archived = finding.archived,
        // Rounded arithmetically, not by formatting and re-parsing: `"%.2f".format`
        // honours the default locale, so on a Polish JVM it produced "0,00" and
        // `toDouble()` threw. A number should never make a round trip through text.
        missingPercent = round(finding.missingFraction * 100 * 100) / 100.0,
        // Surfaced rather than hidden: a non-authoritative match proves the replay
        // finished, not that the source had nothing more to give.
        authoritative = finding.isAuthoritative,
    )

    data class BackfillResponse(
        val connector: String,
        val queued: Int,
        /** Partitions the dedup key refused, because a replay is already running. */
        val alreadyInFlight: Int,
        val partitions: List<String>,
    )

    data class CompletenessResponse(
        val connector: String,
        val complete: Boolean,
        val tolerance: Double,
        val findings: List<FindingResponse>,
        val gaps: List<FindingResponse>,
    )

    data class FindingResponse(
        val partition: String,
        val kind: String,
        val declared: Int,
        val archived: Int,
        val missingPercent: Double,
        val authoritative: Boolean,
    )
}
