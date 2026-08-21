package pl.barometr.legislative.internal

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Service
import pl.barometr.corpus.api.ArchivedDocument
import pl.barometr.corpus.api.DocumentCatalog
import pl.barometr.corpus.api.DocumentKind
import pl.barometr.corpus.api.DocumentVersionRecorded
import java.time.ZoneOffset

/**
 * Pins a document to the act it belongs to, or says it cannot.
 *
 * Three outcomes, and the third is the one that keeps the queue usable. An identifier
 * the publisher stated ends the matter. A title close enough to an act's is taken, or
 * — closer than noise but not close enough to trust — handed to a person. Anything
 * further away is left alone: a print for a bill still in committee has no act to
 * match, and asking someone to confirm that would bury the queue in questions with no
 * answer.
 *
 * Nothing here runs for acts themselves; [EliActProjector] owns those, and its
 * publisher-stated links are what most documents are matched by. This exists for the
 * gap in between — a print whose bill has passed but whose act has not been read yet,
 * or one the register never names.
 */
@Service
class ActIdentityMatcher(
    private val documents: DocumentCatalog,
    private val acts: ActRepository,
    private val identifiers: ActIdentifierRepository,
    private val candidates: ActMatchCandidateRepository,
    private val properties: LegislativeProperties,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @ApplicationModuleListener
    fun resolveDocumentToAct(recorded: DocumentVersionRecorded) {
        if (recorded.kind != SEJM_PRINT) return

        val address = recorded.externalId.value
        if (identifiers.actFor(IdentifierScheme.SEJM_PRINT, address) != null) return

        val document = documents.documentById(recorded.documentId) ?: return
        val title = document.title ?: return

        matchByTitle(document, address, title)
    }

    private fun matchByTitle(document: ArchivedDocument, address: String, title: String) {
        val closest = acts.closestByTitle(
            normalisedTitle = ActTitles.normalise(title),
            // An act published before the print existed cannot be what the print
            // became. Without this the nearest title is often last year's version of
            // the same law.
            notAnnouncedBefore = document.publishedAt?.atZone(ZoneOffset.UTC)?.toLocalDate(),
            atLeast = properties.reviewMatchAbove,
        )

        if (closest == null) {
            meters.counter("legislative.match.outcome", "outcome", "none").increment()
            return
        }

        if (closest.similarity >= properties.automaticMatchAbove) {
            identifiers.pointAtAct(
                IdentifierScheme.SEJM_PRINT,
                address,
                closest.actId,
                MatchMethod.FUZZY,
                closest.similarity,
            )
            meters.counter("legislative.match.outcome", "outcome", "automatic").increment()
            log.debug("Matched {} to {} by title at {}", address, closest.eli, closest.similarity)
            return
        }

        candidates.queueForReview(
            documentId = document.id,
            actId = closest.actId,
            scheme = IdentifierScheme.SEJM_PRINT,
            value = address,
            confidence = closest.similarity,
        )
        meters.counter("legislative.match.outcome", "outcome", "review").increment()
    }

    private companion object {
        val SEJM_PRINT = DocumentKind("print")
    }
}
