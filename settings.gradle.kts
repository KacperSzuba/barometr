// The settings file is the entry point of every Gradle build.
// https://docs.gradle.org/current/userguide/settings_file_basics.html

pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositories {
        mavenCentral()
    }
}

plugins {
    // Downloads the JDK the toolchain asks for, so the build does not depend on
    // whatever JDK happens to be on the machine.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "barometr"

// ——— One module per bounded context ————————————————————————————————————————
//
// A module here is a service candidate: everything one context needs in order to
// run travels with it, so lifting it out later is a move rather than an
// untangling. That is the rule the earlier layout broke — twenty projects, split
// `-api`/`-impl` and by technical layer, so extracting ingestion would have meant
// taking nine of them and five of those were shared with everyone else.
//
// Inside a module, `pl.barometr.<context>.api` is the published contract and
// `pl.barometr.<context>.internal` is everything else, including all persistence.
// The boundary is enforced by Spring Modulith and ArchUnit in `ModularityTest`
// rather than by the build — weaker than a compile error, and the trade is
// deliberate: the boundary that matters now is the one a service extraction would
// actually follow.

include(":app")

// Value types. No Spring, no persistence, no HTTP.
include(":shared")

// Test harness: one migrated Postgres and a movable clock, on the test classpath
// of whatever needs them.
include(":shared-testing")

// Technical capability with no domain meaning: HTTP to the outside world, the job
// queue, object storage, and the extensions every schema rests on. Depends on no
// context; every context may depend on it.
include(":platform")

// ——— Bounded contexts —————————————————————————————————————————————————————
// Directories stay grouped under `modules/`; the project paths are short because
// a module is a top-level thing.
listOf("identity", "sources", "ingestion", "corpus", "legislative", "search", "profiles").forEach { context ->
    include(":$context")
    project(":$context").projectDir = file("modules/$context")
}
