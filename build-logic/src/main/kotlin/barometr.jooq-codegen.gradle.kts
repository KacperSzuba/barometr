import pl.barometr.build.GenerateJooqSources
import pl.barometr.build.JooqCodegenExtension
import pl.barometr.build.JooqCodegenLock
import pl.barometr.build.BuildPostgres
import pl.barometr.build.MigratedPostgresService

plugins {
    id("barometr.module")
}

val codegen = extensions.create<JooqCodegenExtension>("jooqCodegen")

// The build's Postgres, registered by `barometr.kotlin-base` and shared with the
// tests. The same registration rather than a second one: one server, one migration.
val migratedPostgres = BuildPostgres.registerIn(project)

// What serialises generation. It used to be a limit on the service above, which stopped
// working the day the tests began sharing it — the same limit would have run every
// module's tests one after another.
val codegenLock =
    gradle.sharedServices.registerIfAbsent("jooqCodegenLock", JooqCodegenLock::class) {
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
    usesService(codegenLock)

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
