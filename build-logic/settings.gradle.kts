// Build logic lives in an included build rather than `buildSrc`.
//
// With `buildSrc`, any change to a convention plugin invalidates the whole build
// and every project recompiles. An included build only rebuilds what actually
// depends on it — which matters once the project has thirty subprojects.

dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
