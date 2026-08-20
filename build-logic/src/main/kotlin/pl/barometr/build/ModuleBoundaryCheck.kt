package pl.barometr.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Guards the one rule the modular monolith rests on: an implementation module
 * may depend on any other module's `api`, and never on another module's `impl`.
 *
 * Spring Modulith and ArchUnit check the same thing, but only once tests run.
 * This runs at build time against the declared dependency graph, so a violation
 * is a compile-time error rather than something discovered in CI.
 */
abstract class ModuleBoundaryCheck : DefaultTask() {

    @get:Input
    abstract val moduleUnderCheck: Property<String>

    /** Project paths this module illegally reaches into, resolved at configuration time. */
    @get:Input
    abstract val forbiddenDependencies: ListProperty<String>

    @TaskAction
    fun verify() {
        val violations = forbiddenDependencies.get()
        if (violations.isEmpty()) return

        throw GradleException(
            buildString {
                appendLine("Module boundary violation in ${moduleUnderCheck.get()}.")
                appendLine()
                appendLine("It depends on another module's implementation:")
                violations.forEach { appendLine("  → $it") }
                appendLine()
                appendLine("Depend on the corresponding '-api' project instead. If the contract")
                appendLine("you need is missing there, publish it — reaching into an impl couples")
                appendLine("the two modules permanently.")
            },
        )
    }
}
