import org.gradle.api.artifacts.ProjectDependency
import pl.barometr.build.ModuleBoundaryCheck

plugins {
    id("barometr.spring-platform")
}

/**
 * Applied by every library module — `*-api`, `*-impl` and the platform modules.
 * Only `app` is exempt, because wiring the whole application together is exactly
 * its job.
 */

/** Configurations a module declares dependencies in; resolved ones would double-count. */
val declaredConfigurations = setOf("api", "implementation", "compileOnly", "runtimeOnly")

// Captured here: inside the task-configuration block below, `path` would resolve
// to the task's own path rather than the project's.
val modulePath = path

val checkModuleBoundaries = tasks.register<ModuleBoundaryCheck>("checkModuleBoundaries") {
    group = "verification"
    description = "Fails when this module depends on another module's implementation."
    moduleUnderCheck.set(modulePath)
}

afterEvaluate {
    val ownPath = path

    // Captured eagerly into plain strings so the task stays configuration-cache safe.
    val violations = configurations
        .matching { it.name in declaredConfigurations }
        .flatMap { configuration ->
            configuration.dependencies
                .withType(ProjectDependency::class.java)
                .map { it.path }
        }
        .filter { it != ownPath && it.endsWith("-impl") }
        .distinct()
        .sorted()

    checkModuleBoundaries.configure { forbiddenDependencies.set(violations) }
}

tasks.named("check") {
    dependsOn(checkModuleBoundaries)
}
