package pl.barometr.search.internal

import co.elastic.clients.elasticsearch.ElasticsearchClient
import co.elastic.clients.elasticsearch.core.BulkRequest
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Writes entries into an index. Elasticsearch calls only — no reading of anything
 * else, and no decisions about what belongs in the index.
 *
 * Writes go to a named index rather than to the alias, because a rebuild writes into
 * one index while readers are still on another. Everything reading uses the alias;
 * everything writing says which index it means.
 */
@Component
class LegislativeIndexWriter(private val client: ElasticsearchClient) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun write(index: String, entry: IndexedEntry) {
        client.index { request -> request.index(index).id(entry.id).document(entry) }
    }

    /**
     * One request for a page of entries. A rebuild walks a hundred thousand acts, and
     * a request each would spend its whole time on round trips.
     */
    fun writeAll(index: String, entries: List<IndexedEntry>) {
        if (entries.isEmpty()) return

        val operations = entries.map { entry ->
            BulkOperation.Builder()
                .index { it.index(index).id(entry.id).document(entry) }
                .build()
        }
        val response = client.bulk(BulkRequest.Builder().operations(operations).build())

        if (response.errors()) {
            val refused = response.items().mapNotNull { it.error()?.reason() }.distinct().take(3)
            log.warn("Elasticsearch refused {} of {} entries: {}", refused.size, entries.size, refused)
        }
    }
}
