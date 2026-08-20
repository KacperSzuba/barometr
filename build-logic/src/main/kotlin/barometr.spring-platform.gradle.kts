import pl.barometr.build.library
import pl.barometr.build.versionCatalog

plugins {
    id("barometr.kotlin-base")
    // Kotlin classes are final; Spring proxies `@Configuration` and `@Service`
    // beans with CGLIB and refuses to start otherwise. Applied here rather than
    // per module, because every Spring-using project needs it and forgetting it
    // fails at startup, long after compilation said everything was fine.
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = versionCatalog()

dependencies {
    // Gradle platforms rather than the legacy io.spring.dependency-management
    // plugin: one fewer plugin, and version alignment handled by Gradle itself.
    add("implementation", platform(libs.library("springBootBom")))
    add("implementation", platform(libs.library("springModulithBom")))
    add("testImplementation", platform(libs.library("springBootBom")))
    add("testImplementation", platform(libs.library("springModulithBom")))

    add("testImplementation", libs.library("springBootStarterTest"))
    add("testImplementation", kotlin("test"))
}
