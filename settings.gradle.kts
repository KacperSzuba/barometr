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

// ——— The single deployable ————————————————————————————————————————————————
include(":app")

// ——— Shared code ——————————————————————————————————————————————————————————
// Value types only. No logic, no Spring — anything richer belongs to a module.
include(":shared:shared-kernel")
include(":shared:shared-testing")

// ——— Platform ——————————————————————————————————————————————————————————————
// Technical capability with no domain meaning. Modules depend on these; these
// depend on no module.
include(":platform:platform-persistence")
include(":platform:platform-jobs")
include(":platform:platform-storage")
include(":platform:platform-http")

// ——— Domain modules ———————————————————————————————————————————————————————
// Each module is a pair: `-api` publishes the contract, `-impl` keeps everything
// else private. An `-impl` may depend on any `-api` and never on another `-impl`;
// `barometr.module` turns a violation into a build failure.
include(":modules:identity:identity-api")
include(":modules:identity:identity-impl")
include(":modules:sources:sources-api")
include(":modules:sources:sources-impl")
include(":modules:ingestion:ingestion-api")
// Connectors are leaves: each depends on the ingestion SPI and on nothing else.
include(":modules:connectors:connector-sejm")
include(":modules:connectors:connector-rcl")
include(":modules:ingestion:ingestion-impl")
include(":modules:corpus:corpus-api")
include(":modules:corpus:corpus-impl")
include(":modules:legislative:legislative-api")
include(":modules:legislative:legislative-impl")
