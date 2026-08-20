package pl.barometr.shared

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Identifiers are UUIDv7 for one reason — they sort by creation time, so inserts land
 * at the right edge of a B-tree instead of scattering across it. That is a property,
 * not a detail of which library is used, so it is asserted here.
 */
class IdsTest {

    @Test
    fun `identifiers are unique`() {
        val ids = List(10_000) { Ids.next() }

        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `identifiers are version 7, and later ones sort after earlier ones`() {
        val ids = List(1_000) { Ids.next() }

        assertTrue(ids.all { it.version() == 7 }, "UUIDv7 is what gives inserts their locality")
        assertEquals(
            ids.map(java.util.UUID::toString),
            ids.map(java.util.UUID::toString).sorted(),
            "time-ordered identifiers must sort in the order they were created",
        )
    }
}
