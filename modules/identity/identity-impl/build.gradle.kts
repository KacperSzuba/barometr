plugins {
    id("barometr.module")
    // JPA needs a no-arg constructor on every `@Entity`; Kotlin does not emit one.
    // Only modules holding entities need this, so it stays out of the convention.
    alias(libs.plugins.kotlinPluginJpa)
}

dependencies {
    api(project(":modules:identity:identity-api"))
    implementation(project(":shared:shared-kernel"))

    implementation(libs.springBootStarterWeb)
    implementation(libs.springBootStarterSecurity)
    implementation(libs.springBootStarterResourceServer)
    implementation(libs.springBootStarterValidation)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)

    // Identity is the last module on JPA, and is being moved to jOOQ with the rest
    // — see docs/backend-review.md (D-3). The services already talk to the narrow
    // `Users` and `RefreshTokens` ports, so that change stops at this package.
    implementation(libs.springBootStarterDataJpa)

    // Only for the movable clock: rotation, expiry and the grace window are all
    // decisions about time, and a test that sleeps proves nothing about any of them.
    testImplementation(project(":shared:shared-testing"))
}
