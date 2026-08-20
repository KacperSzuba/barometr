package pl.barometr

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import org.springframework.modulith.core.ApplicationModules

/**
 * The architecture's own test suite.
 *
 * Gradle already refuses to compile a module that depends on another module's
 * implementation, which is the primary guard. These are the two things Gradle
 * cannot see: cycles between modules, and `app` — which legitimately sees every
 * implementation — reaching into their internals anyway.
 */
class ModularityTest {

    private val modules = ApplicationModules.of(BarometrApplication::class.java)

    @Test
    fun `module boundaries and dependency directions hold`() {
        modules.verify()
    }

    @Test
    fun `nothing outside a module reaches into its internals`() {
        val classes = ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("pl.barometr")

        noClasses()
            .that().resideOutsideOfPackage("pl.barometr.identity..")
            .should().dependOnClassesThat().resideInAPackage("pl.barometr.identity.internal..")
            .because(
                "identity publishes its contract in `identity-api`; reaching past it couples " +
                    "callers to storage and service internals that are free to change",
            )
            .check(classes)
    }

    /**
     * That each context is recognised as a module at all.
     *
     * `verify()` above checks the relationships between whatever modules Modulith
     * found; it says nothing if a context was not found. A package that stops being
     * a module — renamed, moved under another — would silently drop out of every
     * other check in this class.
     *
     * Replaces a test that printed the structure and asserted nothing. The structure
     * itself is served at runtime by the `modulith` actuator endpoint, which is a
     * better place for something nobody was reading in a build log.
     */
    @Test
    fun `every domain context is an application module`() {
        listOf("identity", "sources", "ingestion", "corpus", "legislative").forEach { context ->
            assertTrue(
                modules.getModuleByName(context).isPresent,
                "no application module named '$context'",
            )
        }
    }
}
