plugins {
    id("barometr.application")
}

dependencies {
    // The only project allowed to see implementations — wiring them together is
    // its entire purpose. `barometr.module` would reject these dependencies,
    // which is exactly why `app` does not apply it.
    implementation(project(":modules:identity:identity-impl"))
    implementation(project(":modules:sources:sources-impl"))
    implementation(project(":modules:ingestion:ingestion-impl"))
    implementation(project(":modules:connectors:connector-sejm"))
    implementation(project(":modules:connectors:connector-rcl"))
    implementation(project(":platform:platform-storage"))
    implementation(project(":platform:platform-http"))
    implementation(project(":modules:corpus:corpus-impl"))
    implementation(project(":modules:legislative:legislative-impl"))
    implementation(project(":platform:platform-persistence"))
    implementation(project(":platform:platform-jobs"))
    implementation(project(":shared:shared-kernel"))

    implementation(libs.springBootStarterWeb)
    implementation(libs.springBootStarterSecurity)
    implementation(libs.springBootStarterResourceServer)
    implementation(libs.springBootStarterActuator)
    implementation(libs.jacksonModuleKotlin)

    implementation(libs.springModulithStarterCore)
    // Persists every published event to `event_publication` and retries delivery,
    // which is what gives inter-module events transactional outbox semantics.
    implementation(libs.springModulithStarterJdbc)
    implementation(libs.springModulithActuator)

    runtimeOnly(libs.springBootStarterFlyway)
    runtimeOnly(libs.flywayPostgresql)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.springModulithTest)
    testImplementation(libs.archunitJunit5)
    // The one test that starts the real context needs a real database, and the
    // schema it starts against must be the one the migrations produce.
    testImplementation(project(":shared:shared-testing"))
}

springBoot {
    mainClass.set("pl.barometr.BarometrApplicationKt")
}
