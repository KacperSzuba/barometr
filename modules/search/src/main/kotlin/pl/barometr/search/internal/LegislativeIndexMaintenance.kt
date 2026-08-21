package pl.barometr.search.internal

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Makes sure the index and its alias exist, and says so loudly when it cannot.
 *
 * A missing index is not a reason to refuse to start. Everything this application does
 * that matters — archiving what a source returned, deriving documents, acts and paths
 * from it — is untouched by search being down, and the index is rebuildable from
 * Postgres whenever it comes back. Refusing to boot would trade the part of the system
 * that cannot be reconstructed for the part that can.
 *
 * Creating it is therefore attempted once at startup and left alone afterwards: a
 * node that appears later gets its index from the rebuild, and a warning here is the
 * signal that somebody has to run one.
 */
@Component
class LegislativeIndexMaintenance(private val client: ElasticsearchClient) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun createIndexWhenAbsent() {
        try {
            if (indexExists()) return

            createIndex()
            log.info("Created search index {} behind alias {}", LegislativeIndex.CURRENT, LegislativeIndex.ALIAS)
        } catch (unreachable: Exception) {
            log.warn(
                "No search index: {}. Ingestion and derivation are unaffected; search " +
                    "answers nothing until the index is rebuilt.",
                unreachable.message,
            )
        }
    }

    fun indexExists(): Boolean = client.indices().exists { it.index(LegislativeIndex.CURRENT) }.value()

    /**
     * Created with the alias attached in the same call, so there is never a moment
     * where the index exists and nothing can find it.
     */
    fun createIndex() {
        val definition = requireNotNull(javaClass.getResourceAsStream(LegislativeIndex.DEFINITION)) {
            "Missing index definition ${LegislativeIndex.DEFINITION}"
        }

        definition.use {
            client.indices().create(
                CreateIndexRequest.Builder()
                    .index(LegislativeIndex.CURRENT)
                    .withJson(it)
                    .aliases(LegislativeIndex.ALIAS) { alias -> alias }
                    .build(),
            )
        }
    }
}
