package pl.barometr

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules
import kotlin.test.assertTrue

/**
 * The architecture's own test suite.
 *
 * This is now the *only* thing enforcing module boundaries. The build used to fail
 * when a module depended on another module's `-impl` project; consolidating each
 * context into one module removed those projects, and with them the compile-time
 * guard. What replaces it is here, and it runs in `check`.
 *
 * The trade was deliberate — see docs/backend-review.md (D-1) — but it does mean
 * these tests are load-bearing rather than reassuring. A context added without a
 * line in [CONTEXTS] is a context nobody is checking.
 */
class ModularityTest {

    private val modules = ApplicationModules.of(BarometrApplication::class.java)

    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("pl.barometr")

    @Test
    fun `module boundaries and dependency directions hold`() {
        modules.verify()
    }

    /**
     * That each context is recognised as a module at all.
     *
     * `verify()` checks the relationships between whatever modules Modulith found;
     * it says nothing if a context was not found. A package that stops being a
     * module — renamed, or moved under another — would silently drop out of every
     * other check in this class.
     *
     * Replaces a test that printed the structure and asserted nothing. The structure
     * itself is served at runtime by the `modulith` actuator endpoint, which is a
     * better place for something nobody was reading in a build log.
     */
    @Test
    fun `every context is an application module`() {
        CONTEXTS.forEach { context ->
            assertTrue(
                modules.getModuleByName(context).isPresent,
                "no application module named '$context'",
            )
        }
    }

    /**
     * The rule the whole layout rests on: a context publishes a contract, and
     * everything else it owns is unreachable from outside.
     *
     * Checked for every context rather than for the one that happened to have a
     * rule written for it. Since each context is now a single Gradle module, the
     * compiler will happily let one reach into another's `internal` package; this
     * is what says no.
     */
    @Test
    fun `nothing outside a context reaches into its internals`() {
        CONTEXTS.forEach { context ->
            noClasses()
                .that().resideOutsideOfPackage("pl.barometr.$context..")
                .should().dependOnClassesThat().resideInAPackage("pl.barometr.$context.internal..")
                .because(
                    "$context publishes its contract in `pl.barometr.$context.api`; reaching " +
                        "past it couples callers to storage and service internals that are " +
                        "free to change",
                )
                .check(classes)
        }
    }

    private companion object {
        /**
         * Every package under `pl.barometr` that owns internals of its own: the five
         * bounded contexts, and the three technical capabilities that make up
         * `platform`. `shared` and `testing` are absent because they have no internals
         * to hide.
         *
         * `connectors` is absent for a different reason. It does now publish one
         * contract — `connectors.rcl.api`, the page model two other contexts read RPL's
         * archived HTML through — but its own packages are `sejm`, `rcl` and `support`
         * rather than an `internal`, so the rule below has nothing to match. What
         * protects it is Spring Modulith: with a named interface declared, everything
         * else in that module is internal to it, and `verify()` says so.
         */
        val CONTEXTS = listOf(
            "identity",
            "sources",
            "ingestion",
            "corpus",
            "legislative",
            "search",
            "platform",
            "http",
            "storage",
        )
    }
}
