package pl.barometr.build

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider

/**
 * Version catalog access for precompiled script plugins.
 *
 * The generated `libs` accessor is not available inside `build-logic`, so the
 * catalog is looked up through its extension instead.
 */
fun Project.versionCatalog(): VersionCatalog =
    extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

fun VersionCatalog.library(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow {
        IllegalStateException("No library '$alias' declared in gradle/libs.versions.toml")
    }
