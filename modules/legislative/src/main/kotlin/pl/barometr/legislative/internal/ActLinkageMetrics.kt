package pl.barometr.legislative.internal

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.springframework.stereotype.Component
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentKind

/**
 * How much of the archive is pinned to an act.
 *
 * The product's own measure of whether identity resolution is working: a user looking
 * at a print, a draft and a published act should be looking at one case, and the share
 * of documents that reach an act is what says whether they are.
 *
 * Published as two counts per kind rather than as a ratio, because the ratio is a
 * division a dashboard can do and a third gauge would be the same fact stored twice.
 * The counts are of different populations on purpose, and the difference is
 * informative: the register can name a print from a term we never archived, so
 * `linked` can exceed `matchable` — and when it does, the archive is short of a term,
 * which is worth seeing rather than smoothing away.
 */
@Component
class ActLinkageMetrics(
    private val documents: DocumentCatalog,
    private val identifiers: ActIdentifierRepository,
    private val candidates: ActMatchCandidateRepository,
) : MeterBinder {

    /**
     * A [MeterBinder] rather than a constructor that registers itself: Boot binds
     * every one of them to every registry it creates, so the gauges exist wherever
     * metrics are collected without this class knowing which registry that is.
     */
    override fun bindTo(meters: MeterRegistry) {
        MATCHABLE.forEach { (kind, scheme) ->
            Gauge.builder("legislative.documents.matchable") { documents.countByKind()[kind] ?: 0 }
                .tag("kind", kind.value)
                .description("Documents of a kind that can be pinned to an act")
                .register(meters)

            Gauge.builder("legislative.documents.linked") { identifiers.countByScheme()[scheme] ?: 0 }
                .tag("kind", kind.value)
                .description("Identifiers of the scheme those documents are pinned by")
                .register(meters)
        }

        Gauge.builder("legislative.act_matches.awaiting_review") { candidates.countAwaitingReview() }
            .description("Matches waiting for a person to decide")
            .register(meters)
    }

    private companion object {
        /**
         * Which kinds of document are expected to reach an act at all, and by which
         * identifier. A club or a member never does, and counting them would make the
         * share look bad for a reason that is not a fault.
         */
        val MATCHABLE = mapOf(
            DocumentKind("print") to IdentifierScheme.SEJM_PRINT,
            DocumentKind("act") to IdentifierScheme.ELI,
        )
    }
}
