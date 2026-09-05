package pl.barometr.taxonomy.internal

import io.micrometer.core.instrument.MeterRegistry
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.barometr.legislative.api.ActId
import pl.barometr.legislative.api.DraftId
import pl.barometr.legislative.api.LegislativeCatalog
import pl.barometr.legislative.api.LegislativeKind
import java.util.UUID

/**
 * Reads the archive that was already there when the classifier arrived.
 *
 * [LegislativeClassificationListener] catches everything from the edge onwards, and on
 * the day this ships the edge holds one act. Everything the ingestion of the last year
 * put in the archive was recorded before anything was listening, and no event will be
 * raised for it again — an act nobody amends never produces a second version. Without
 * this walk the whole promise of routing by industry would apply to whatever happens
 * next and to nothing that already happened.
 *
 * **Where it got to is written down, per lexicon version.** A restart resumes rather
 * than starting over, and — the part that matters more — correcting the terms is what
 * makes the archive worth reading again: a new version has no progress recorded, so the
 * walk begins from the beginning and every subject meets the terms somebody has just
 * fixed. That is the intended way to improve coverage, and it costs an edit to a file.
 *
 * **It stops when it runs out of archive.** A walk whose only ending is the end of the
 * archive would page through a hundred thousand acts every hour to find out there is
 * nothing left; the completion mark is what makes the steady state free.
 */
@Component
class UnclassifiedBacklogSweep(
    private val catalogue: LegislativeCatalog,
    private val tagging: LegislationTagging,
    private val classifier: LexicalIndustryClassifier,
    private val progress: ClassificationProgressRepository,
    private val properties: ClassificationProperties,
    private val meters: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${app.taxonomy.backlog-sweep-interval:PT15M}", initialDelay = 240_000)
    @SchedulerLock(name = "taxonomy-backlog-sweep")
    fun classifyWhatTheArchiveAlreadyHolds() {
        readThrough(LegislativeKind.ACT) { after, limit ->
            catalogue.actsAfter(after?.let(::ActId), limit).map { Subject(it.id.value, it.title) }
        }
        readThrough(LegislativeKind.DRAFT) { after, limit ->
            catalogue.draftsAfter(after?.let(::DraftId), limit).map { Subject(it.id.value, it.title) }
        }
    }

    /**
     * One kind, from wherever the last run stopped, until the budget or the archive
     * runs out.
     *
     * The budget counts subjects looked at rather than verdicts written: a page of
     * titles that mention no industry this lexicon knows is work done, and counting
     * only what was classified would let a stretch of tax law hold the walk still.
     */
    private fun readThrough(kind: String, page: (UUID?, Int) -> List<Subject>) {
        val version = classifier.version
        val standing = progress.progressOf(version, kind)
        if (standing.completed) return

        var after = standing.lastSubjectId
        var read = 0

        while (read < properties.subjectsPerSweep) {
            // Never more than the run has left to spend: a page bigger than the budget
            // would make the bound a suggestion, and a first run on a hundred thousand
            // acts is exactly where that matters.
            val subjects = page(after, minOf(PAGE, properties.subjectsPerSweep - read))
            if (subjects.isEmpty()) {
                progress.recordCompletion(version, kind)
                log.info("Lexicon {} has read every {} in the archive", version, kind)

                return
            }

            subjects.forEach { subject -> tagging.tagSubject(kind, subject.id, subject.title) }
            read += subjects.size
            after = subjects.last().id
            progress.recordPosition(version, kind, subjects.last().id)
        }

        meters.counter("taxonomy.backlog.read", "kind", kind).increment(read.toDouble())
        log.info("Classified {} archived {}s against lexicon {}", read, kind, version)
    }

    /** One subject, reduced to what classifying it needs: which row it is, and its title. */
    private data class Subject(val id: UUID, val title: String)

    private companion object {
        /**
         * At most how many the catalogue is asked for at a time. Small beside the
         * budget so that the position is written often enough for a restart to lose a
         * page rather than an afternoon.
         */
        const val PAGE = 100
    }
}
