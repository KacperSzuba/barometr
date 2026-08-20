package pl.barometr.connectors.sejm

import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.SerializationFeature

/**
 * Renders an entity to the bytes that will be hashed and stored.
 *
 * Keys are sorted at *every* level, so an entity the source decides to emit in a
 * different field order still resolves to the same content address. Without it,
 * deduplication would quietly depend on somebody else's serialiser never
 * changing — and the day it did, the archive would double.
 *
 * `ORDER_MAP_ENTRIES_BY_KEYS` rather than deserialising into a `TreeMap`: a tree
 * map type sorts only the top level, because nested objects still become plain
 * linked maps and keep the order they arrived in.
 *
 * Belongs to a shared connector-support module once a second connector needs it.
 */
class CanonicalJsonPayload(private val json: ObjectMapper) {

    private val writer = json.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    fun bytesOf(entity: SejmEntity): ByteArray =
        writer.writeValueAsBytes(json.treeToValue(entity.body, Map::class.java))
}
