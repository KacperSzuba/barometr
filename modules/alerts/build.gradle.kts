plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "alerts"
}

// Who gets told what, and — as much to the point — who does not.
//
// The context that turns "this act moved" into "you, specifically, should know", and
// the one place that can answer "why did I get this" and "why did I not". It decides
// nothing about what a profile means: that question is asked of profiles, so the
// preview somebody sees while editing and the run that wakes them at seven cannot
// disagree.
dependencies {
    api(project(":shared"))
    api(project(":identity"))
    implementation(project(":platform"))
    // Who is interested, asked of the context that owns the answer.
    implementation(project(":profiles"))
    // What the thing that moved actually is: an event names it and nothing more.
    implementation(project(":legislative"))

    implementation(libs.springBootStarter)
    implementation(libs.springBootStarterJooq)
    implementation(libs.springBootStarterWeb)
    implementation(libs.springBootStarterValidation)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)
    // The buffer fills from legislative's events; the register that persists and
    // redelivers them is wired in :app.
    implementation(libs.springModulithEventsApi)
    implementation(libs.springSecurityCore)
    // The matching run happens once across the deployment, not once per instance.
    implementation(libs.shedlockSpring)

    testImplementation(project(":shared-testing"))
    testImplementation(libs.testcontainersJunit)
    testImplementation(kotlin("test"))
}
