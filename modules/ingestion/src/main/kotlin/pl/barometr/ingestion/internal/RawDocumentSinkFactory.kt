package pl.barometr.ingestion.internal

import org.springframework.stereotype.Component
import pl.barometr.sources.api.SourceId
import java.util.UUID

/**
 * Hands a connector a sink tied to one source and one run.
 *
 * Binding rather than parameterising is the point: a connector receives a sink that
 * can only write to the source it was started for, so writing to the wrong source is
 * not a mistake it is able to make.
 */
@Component
class RawDocumentSinkFactory(private val archiver: RawDocumentArchiver) {

    fun forRun(sourceId: SourceId, runId: UUID?): RunBoundRawDocumentSink =
        RunBoundRawDocumentSink(archiver, sourceId, runId)
}
