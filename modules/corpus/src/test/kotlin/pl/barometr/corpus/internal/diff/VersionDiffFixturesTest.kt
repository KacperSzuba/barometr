package pl.barometr.corpus.internal.diff

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import pl.barometr.corpus.api.ChangeKind
import pl.barometr.corpus.api.UnitChange
import pl.barometr.corpus.internal.structure.ComparableText
import pl.barometr.corpus.internal.structure.EditorialUnitReader
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The acceptance criterion, as twenty-one pairs of versions.
 *
 * The specification asks for one thing above all: over a set of hand-checked pairs, a
 * renumbering must not be reported as a deletion. Two of the assertions below are
 * therefore made about every pair, whatever it is testing:
 *
 * - **nothing is reported as removed while its words are still in the newer version**,
 *   which is the false deletion the whole alignment exists to prevent;
 * - **nothing is reported as added while its words were already in the older one**,
 *   which is the same failure seen from the other end.
 *
 * On top of that each fixture states what it expects — how many units were added,
 * removed, modified and moved — in an `expected.txt` beside the two versions. A
 * fixture states only the counts it is about; the two invariants above hold for all of
 * them.
 *
 * The pairs are written rather than downloaded, and that is a deliberate limit worth
 * stating: they reproduce the *shapes* RPL publishes — an article inserted mid-bill, a
 * point deleted from a list, a chapter added above the articles, a word broken by a
 * page break — at a size a person can check by eye. A real three-hundred-page pair is
 * not something anybody can verify in a test, which is what
 * [VersionDiffPerformanceTest] is for.
 */
class VersionDiffFixturesTest {

    private val reader = EditorialUnitReader()
    private val alignment = UnitAlignment(DiffProperties())
    private val words = WordLevelChanges(DiffProperties())

    @TestFactory
    fun `every recorded pair of versions is read the way it was checked by hand`(): List<DynamicTest> =
        scenarios().map { scenario ->
            DynamicTest.dynamicTest(scenario.name) { verify(scenario) }
        }

    @Test
    fun `there are at least twenty pairs to check`() {
        assertTrue(scenarios().size >= 20, "the specification asks for twenty verified pairs")
    }

    private fun verify(scenario: Path) {
        val before = scenario.resolve("before.txt").readText()
        val after = scenario.resolve("after.txt").readText()
        val changes = changesBetween(before, after)

        assertNoFalseRemoval(changes, before, after)
        assertNoFalseAddition(changes, before, after)
        assertQuotesResolve(changes, before, after)
        assertExpected(scenario, changes)
    }

    /**
     * A removal whose words are still somewhere in the newer version, as a unit, is the
     * failure this whole feature exists to avoid: the reader is told a provision was
     * deleted when it was renumbered.
     */
    private fun assertNoFalseRemoval(changes: List<UnitChange>, before: String, after: String) {
        val stillThere = unitsOf(after)

        changes.filter { it.kind == ChangeKind.REMOVED }.forEach { change ->
            val removed = comparableOf(before, change.fromCharStart, change.fromCharEnd)
            assertTrue(
                removed.isEmpty() || removed !in stillThere,
                "reported as removed while the newer version still says it: '$removed'",
            )
        }
    }

    private fun assertNoFalseAddition(changes: List<UnitChange>, before: String, after: String) {
        val wasThere = unitsOf(before)

        changes.filter { it.kind == ChangeKind.ADDED }.forEach { change ->
            val added = comparableOf(after, change.toCharStart, change.toCharEnd)
            assertTrue(
                added.isEmpty() || added !in wasThere,
                "reported as added while the older version already said it: '$added'",
            )
        }
    }

    /** A range of a version, in the reading two units are compared by. */
    private fun comparableOf(text: String, start: Int?, end: Int?): String =
        if (start == null || end == null) {
            ""
        } else {
            ComparableText.comparableOf(ComparableText.wordsIn(text, start, end))
        }

    private fun assertQuotesResolve(changes: List<UnitChange>, before: String, after: String) {
        changes.forEach { change ->
            change.fromCharEnd?.let { assertTrue(it <= before.length, "a range past the end of the older version") }
            change.toCharEnd?.let { assertTrue(it <= after.length, "a range past the end of the newer version") }
            change.words.forEach { word ->
                word.fromCharEnd?.let { assertTrue(it <= before.length, "a word range past the older version") }
                word.toCharEnd?.let { assertTrue(it <= after.length, "a word range past the newer version") }
            }
        }
    }

    private fun assertExpected(scenario: Path, changes: List<UnitChange>) {
        val counted = mapOf(
            "added" to changes.count { it.kind == ChangeKind.ADDED },
            "removed" to changes.count { it.kind == ChangeKind.REMOVED },
            "modified" to changes.count { it.kind == ChangeKind.MODIFIED },
            "moved" to changes.count { it.kind == ChangeKind.MOVED },
            "substantive" to changes.count(UnitChange::substantive),
        )

        expectationsIn(scenario).forEach { (what, expected) ->
            assertEquals(expected, counted[what], "$what in ${scenario.name}: $changes")
        }
    }

    /**
     * The changes, produced the way [VersionComparison] produces them but without the
     * archive: what is under test is the reading, and a blob store would only add a
     * temporary directory to it.
     */
    private fun changesBetween(before: String, after: String): List<UnitChange> {
        val older = reader.unitsIn(before).map { UnitReading.of(before, it) }
        val newer = reader.unitsIn(after).map { UnitReading.of(after, it) }

        return alignment.alignedTo(older, newer).map { aligned ->
            UnitChange(
                kind = aligned.kind,
                unitKind = (aligned.after ?: aligned.before)!!.unit.kind.wireName,
                substantive = aligned.substantive,
                fromPath = aligned.before?.path,
                fromCharStart = aligned.before?.unit?.charStart,
                fromCharEnd = aligned.before?.unit?.charEnd,
                toPath = aligned.after?.path,
                toCharStart = aligned.after?.unit?.charStart,
                toCharEnd = aligned.after?.unit?.charEnd,
                similarity = aligned.similarity,
                words = wordsOf(aligned),
                wordsTruncated = false,
            )
        }
    }

    private fun wordsOf(aligned: AlignedUnits) =
        if (aligned.kind == ChangeKind.MODIFIED && aligned.before != null && aligned.after != null) {
            words.changesWithin(aligned.before, aligned.after).changes
        } else {
            emptyList()
        }

    /** Every unit of a version in the reading two units are compared by. */
    private fun unitsOf(text: String): Set<String> =
        reader.unitsIn(text)
            .map { ComparableText.comparableOf(ComparableText.wordsIn(text, it.charStart, it.charEnd)) }
            .filter(String::isNotEmpty)
            .toSet()

    private fun expectationsIn(scenario: Path): Map<String, Int> =
        scenario.resolve("expected.txt").readText()
            .lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (what, count) = line.split("=", limit = 2)
                what.trim() to count.trim().toInt()
            }

    private fun scenarios(): List<Path> =
        Path.of(checkNotNull(javaClass.getResource("/fixtures/diff")) { "no diff fixtures" }.toURI())
            .listDirectoryEntries()
            .filter(Path::isDirectory)
            .sortedBy(Path::name)
}
