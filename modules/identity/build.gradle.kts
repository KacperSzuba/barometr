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
    // TOTP for the second factor: the counter, the truncation and a constant-time
    // comparison are exactly the three things a hand-rolled version gets wrong.
    implementation(libs.javaOtp)
    // Turns the address a session signed in from into "Warszawa, PL", from a file the
    // deployment supplies. Nothing leaves the machine to do it.
    implementation(libs.maxmindDb)
    // MeterRegistry: how often an account is closed and how much an export weighs are
    // numbers a data-protection register is asked for, not queries somebody remembers.
    implementation(libs.springBootStarterActuator)
    // Retention runs once across the deployment, not once per instance: two sweeps racing
    // would each try to delete the other's files.
    implementation(libs.shedlockSpring)

    // The movable clock — rotation, expiry, the grace window and a TOTP step are all
    // decisions about time, and a test that sleeps proves nothing about any of them —
    // and the migrated database the session and second-factor tables are tested against.
    testImplementation(project(":shared-testing"))
    testImplementation(libs.testcontainersJunit)
}
