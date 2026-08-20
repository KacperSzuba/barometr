package pl.barometr.ingestion.internal

import org.springframework.stereotype.Component
import pl.barometr.platform.ClaimedJob
import pl.barometr.platform.JobQueue
import pl.barometr.platform.JobType
import pl.barometr.platform.NewJob
import pl.barometr.sources.api.IngestionMode
import pl.barometr.sources.api.SourceDefinition
import pl.barometr.sources.api.SourceId
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * The only way an ingestion run reaches the queue.
 *
 * Three things have to agree for a run to survive the trip through the queue: the
 * job type a handler registers against, the dedup key that decides whether the work
 * is already in flight, and the payload format. They live here together because they
 * are one fact; previously they were a companion function two other classes reached
 * into, and a payload built by string interpolation — so a partition key containing
 * a quote produced JSON the handler could not read, and the job dead-lettered after
 * five attempts.
 */
@Component
class IngestionRunQueue(
    private val queue: JobQueue,
    private val json: ObjectMapper,
) {

    /**
     * Queues one run. Returns false when the same work is already pending or
     * running — the dedup key's decision, taken by the database rather than by a
     * read-then-write race between producers.
     */
    fun queueRun(
        source: SourceDefinition,
        mode: IngestionMode,
        runAfter: Instant,
        partition: String = "",
    ): Boolean = queue.enqueue(
        NewJob(
            type = TYPE,
            payload = json.writeValueAsString(
                WirePayload(
                    sourceId = source.id.value.toString(),
                    mode = mode.wireName,
                    partition = partition,
                ),
            ),
            runAfter = runAfter,
            priority = when (mode) {
                IngestionMode.INCREMENTAL -> NewJob.DEFAULT_PRIORITY
                // Below live ingestion, so a five-year replay never delays today.
                IngestionMode.BACKFILL -> NewJob.BACKGROUND
            },
            // The partition is part of the key, so every partition of a backfill can
            // be queued at once while each stays unique.
            dedupKey = dedupKeyFor(source, mode, partition),
        ),
    )

    fun requestOf(job: ClaimedJob): IngestionRunRequest {
        val wire = json.readValue(job.payload, WirePayload::class.java)
        return IngestionRunRequest(
            sourceId = SourceId(UUID.fromString(wire.sourceId)),
            mode = IngestionMode.of(wire.mode),
            partition = wire.partition,
        )
    }

    private fun dedupKeyFor(
        source: SourceDefinition,
        mode: IngestionMode,
        partition: String,
    ): String =
        "ingestion:${source.connectorId.value}:${mode.wireName}" +
            partition.takeIf { it.isNotEmpty() }?.let { ":$it" }.orEmpty()

    /**
     * What actually sits in the queue's `jsonb` column.
     *
     * Primitive on purpose: a wire format holds wire forms — the mode's `wireName`,
     * the id as text — and is converted to domain types at the boundary above, in
     * one place, where a malformed value fails with something a reader can act on.
     */
    internal data class WirePayload(
        val sourceId: String,
        val mode: String,
        val partition: String,
    )

    companion object {
        val TYPE = JobType("ingestion.run")
    }
}
