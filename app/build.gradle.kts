plugins {
    id("barometr.application")
}

dependencies {
    // The only project that sees every context — assembling them is its entire
    // purpose, and it holds no domain logic of its own.
    implementation(project(":shared"))
    implementation(project(":platform"))
    implementation(project(":identity"))
    implementation(project(":sources"))
    implementation(project(":ingestion"))
    implementation(project(":corpus"))
    implementation(project(":legislative"))
    implementation(project(":search"))
    implementation(project(":profiles"))

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

    runtimeOnly(libs.springBootStarterLiquibase)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.springModulithTest)
    // Authorization is asserted against the real chain: an operator endpoint that
    // stops being one is a security defect, and nothing else in the build would see it.
    testImplementation(libs.springSecurityTest)
    testImplementation(libs.springBootStarterWebmvcTest)
    testImplementation(libs.springBootSecurityTest)
    testImplementation(libs.archunitJunit5)
    // The one test that starts the real context needs a real database, and the
    // schema it starts against must be the one the migrations produce.
    testImplementation(project(":shared-testing"))
}

springBoot {
    mainClass.set("pl.barometr.BarometrApplicationKt")
}
