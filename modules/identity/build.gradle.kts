plugins {
    id("barometr.module")
    // JPA needs a no-arg constructor on every `@Entity`; Kotlin does not emit one.
    // Only this context holds entities, so it stays out of the convention — and
    // goes entirely once identity moves to jOOQ with everything else.
    alias(libs.plugins.kotlinPluginJpa)
}

dependencies {
    api(project(":shared"))

    implementation(libs.springBootStarterWeb)
    implementation(libs.springBootStarterSecurity)
    implementation(libs.springBootStarterResourceServer)
    implementation(libs.springBootStarterValidation)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)

    // The last module on JPA — see docs/backend-review.md (D-3). The services talk
    // to the narrow `Users` and `RefreshTokens` ports, so that change stops at the
    // adapters in `internal.user`.
    implementation(libs.springBootStarterDataJpa)

    // Only for the movable clock: rotation, expiry and the grace window are all
    // decisions about time, and a test that sleeps proves nothing about any of them.
    testImplementation(project(":shared-testing"))
}
