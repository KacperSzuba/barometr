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
    implementation(project(":taxonomy"))
    implementation(project(":audit"))
    implementation(project(":alerts"))

    implementation(libs.springBootStarterWeb)
    implementation(libs.springBootStarterSecurity)
    implementation(libs.springBootStarterResourceServer)
    implementation(libs.springBootStarterActuator)
    // One trace per document, from the request that started it to the alert it became.
    implementation(libs.springBootStarterOpenTelemetry)
    runtimeOnly(libs.micrometerPrometheus)
    implementation(libs.jacksonModuleKotlin)
    // The API contract, generated from the controllers. The application declares it
    // because only the application knows every context's routes — the same reason the
    // security chain lives here.
    implementation(libs.springdocWebmvc)

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
    // The end-to-end second-factor test plays the part of the authenticator app: it has
    // to produce the code a phone would show, which is the one thing a caller of this
    // API cannot be given by the API.
    testImplementation(libs.javaOtp)
    // The one test that starts the real context needs a real database, and the
    // schema it starts against must be the one the migrations produce.
    testImplementation(project(":shared-testing"))
}

springBoot {
    mainClass.set("pl.barometr.BarometrApplicationKt")
}
