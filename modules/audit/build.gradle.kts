plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "audit"
}

// What was done, by whom, and — the part that matters — what was refused.
//
// Depends on almost nothing on purpose. An audit trail that needed to understand what
// it was recording would have to be changed every time anything else was, and the day
// somebody skipped that change is the day the log stopped being complete. It is told
// what happened in words it already knows.
dependencies {
    api(project(":shared"))
    // An actor is a user, and that identifier is identity's to define.
    api(project(":identity"))
    implementation(project(":platform"))

    implementation(libs.springBootStarter)
    implementation(libs.springBootStarterJooq)
    implementation(libs.springBootStarterWeb)
    // Reading somebody else's history is an operator's business, so the annotation is.
    implementation(libs.springSecurityCore)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)
    // Sessions this system ends on its own are announced by identity, and recorded
    // here: the register that persists and redelivers those events is wired in :app.
    implementation(libs.springModulithEventsApi)

    testImplementation(project(":shared-testing"))
    testImplementation(libs.testcontainersJunit)
    testImplementation(kotlin("test"))
}
