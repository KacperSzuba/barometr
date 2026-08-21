plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "legislative"
}

// Acts, drafts, and the path a draft takes through the legislative process.
dependencies {
    api(project(":shared"))
    api(project(":corpus"))
    implementation(project(":platform"))

    implementation(libs.springBootStarter)
    implementation(libs.springBootStarterJooq)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)
    // Identity resolution runs off corpus's events, so it needs the annotation; the
    // register that persists and redelivers them is wired in :app.
    implementation(libs.springModulithEventsApi)
    // The review queue is an operator endpoint. The chain that authenticates it is
    // the application's, so only the annotations are needed here.
    implementation(libs.springBootStarterWeb)
    implementation(libs.springSecurityCore)
    // MeterRegistry: how much of the archive is pinned to an act is a number worth
    // watching, not a query someone remembers to run.
    implementation(libs.springBootStarterActuator)

    testImplementation(project(":shared-testing"))
    testImplementation(libs.testcontainersJunit)
    testImplementation(kotlin("test"))
}
