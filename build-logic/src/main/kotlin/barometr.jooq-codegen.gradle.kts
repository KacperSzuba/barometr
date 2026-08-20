import pl.barometr.build.GenerateJooqSources
import pl.barometr.build.JooqCodegenExtension
import pl.barometr.build.MigratedPostgresService

plugins {
    id("barometr.module")
}

val codegen = extensions.create<JooqCodegenExtension>("jooqCodegen")

// Shared across every module that generates code: one container per build,
// migrated once, stopped when the build ends.
val migratedPostgres =
    gradle.sharedServices.registerIfAbsent("migratedPostgres", MigratedPostgresService::class) {
        parameters.rootDirectory.set(rootProject.layout.projectDirectory)
        // jOOQ's generation tool is not built for concurrent use against one
        // connection, and codegen is fast enough that serialising costs nothing.
        maxParallelUsages.set(1)
    }

val allChangelogs = rootProject.layout.projectDirectory.asFileTree.matching {
    include("**/src/main/resources/db/changelog/**")
    exclude("**/build/**")
}

val generateJooq = tasks.register<GenerateJooqSources>("generateJooq") {
    group = "build"
    description = "Generates jOOQ sources for this context's schema from the migrated database."

    postgres.set(migratedPostgres)
    usesService(migratedPostgres)

    schemaName.set(codegen.schema)
    // Generated code lands inside the module's `internal` package, so it is
    // unreachable from other modules for exactly the same reason the rest of the
    // implementation is.
    packageName.set(codegen.schema.map { "pl.barometr.$it.internal.jooq" })
    migrations.from(allChangelogs)
    outputDirectory.set(layout.buildDirectory.dir("generated/jooq"))
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateJooq)
}
