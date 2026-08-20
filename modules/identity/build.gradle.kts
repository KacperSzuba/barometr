plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "identity"
}

dependencies {
    api(project(":shared"))
    implementation(project(":platform"))

    implementation(libs.springBootStarterWeb)
    implementation(libs.springBootStarterSecurity)
    implementation(libs.springBootStarterResourceServer)
    implementation(libs.springBootStarterValidation)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)
    implementation(libs.springBootStarterJooq)

    // Only for the movable clock: rotation, expiry and the grace window are all
    // decisions about time, and a test that sleeps proves nothing about any of them.
    testImplementation(project(":shared-testing"))
}
