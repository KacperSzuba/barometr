plugins {
    `kotlin-dsl`
}

// Without this, `kotlin-dsl` compiles the convention plugins with whatever JVM
// runs Gradle. On JDK 25 that means Kotlin silently falls back to target 24
// while javac stays on 25, and every build reports the mismatch.
kotlin {
    jvmToolchain(25)
}

dependencies {
    // Plugin artifacts have to be on the build classpath for a precompiled script
    // plugin to apply them; the `plugins {}` DSL is not available there.
    implementation(libs.kotlinGradlePlugin)
    implementation(libs.kotlinAllOpenPlugin)
    implementation(libs.kotlinNoArgPlugin)
    implementation(libs.springBootGradlePlugin)

    // Code generation runs inside the build, not through a third-party plugin:
    // a container is started, Liquibase migrates it, jOOQ reads the result.
    implementation(libs.buildJooqCodegen)
    implementation(libs.buildLiquibaseCore)
    implementation(libs.buildPostgresql)
    implementation(libs.buildTestcontainersPostgres)
}
