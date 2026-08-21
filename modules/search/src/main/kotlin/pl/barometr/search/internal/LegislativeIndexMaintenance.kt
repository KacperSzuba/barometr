package pl.barometr.search.internal

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Owns the alias: creates an index behind it when there is none, and moves it when a
 * rebuild finishes.
 *
 * A missing index is not a reason to refuse to start. Everything this application does
 * that matters — archiving what a source returned, and deriving documents, acts and
 * paths from it — is untouched by search being down, and the index is rebuildable from
 * Postgres whenever it comes back. Refusing to boot would trade the part of the system
 * that cannot be reconstructed for the part that can.
 */
@Component
class LegislativeIndexMaintenance(
    private val client: ElasticsearchClient,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun createIndexWhenAbsent() {
        try {
            if (aliasExists()) return

            val index = createIndex()
            pointAliasAt(index)
            log.info("Created search index {} behind alias {}", index, LegislativeIndex.ALIAS)
        } catch (unreachable: Exception) {
            log.warn(
                "No search index: {}. Ingestion and derivation are unaffected; search " +
                    "answers nothing until the index is rebuilt.",
                unreachable.message,
            )
        }
    }

    fun aliasExists(): Boolean = client.indices().existsAlias { it.name(LegislativeIndex.ALIAS) }.value()

    /** A fresh index, named for now, with the mapping and the analyser in it. */
    fun createIndex(): String {
        val name = LegislativeIndex.nameFor(clock.instant())
        val definition = requireNotNull(javaClass.getResourceAsStream(LegislativeIndex.DEFINITION)) {
            "Missing index definition ${LegislativeIndex.DEFINITION}"
        }

        definition.use {
            client.indices().create(CreateIndexRequest.Builder().index(name).withJson(it).build())
        }

        return name
    }

    /**
     * Moves the alias in one call — removing it from whatever held it and adding it to
     * [index] — so there is no instant where a search finds nothing.
     */
    fun pointAliasAt(index: String) {
        val previous = indicesBehindAlias()

        client.indices().updateAliases { request ->
            previous.forEach { old ->
                request.actions { action -> action.remove { it.index(old).alias(LegislativeIndex.ALIAS) } }
            }
            request.actions { action -> action.add { it.index(index).alias(LegislativeIndex.ALIAS) } }
        }

        // Dropped only after the alias has moved: until then they are what search is
        // answering from.
        previous.filterNot { it == index }.forEach { old ->
            client.indices().delete { it.index(old) }
            log.info("Dropped superseded search index {}", old)
        }
    }

    private fun indicesBehindAlias(): List<String> =
        if (!aliasExists()) {
            emptyList()
        } else {
            client.indices().getAlias { it.name(LegislativeIndex.ALIAS) }.aliases().keys.toList()
        }
}
