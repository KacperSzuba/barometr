plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "profiles"
}

// What a subscriber has told us they care about. The structure the whole impact
// routing stands on, and nothing else: matching lives with the alerts that do it.
dependencies {
    api(project(":shared"))
    // A profile belongs to somebody, and that identifier is identity's to define.
    api(project(":identity"))
    implementation(project(":platform"))

    implementation(libs.springBootStarter)
    implementation(libs.springBootStarterJooq)
    implementation(libs.springBootStarterWeb)
    implementation(libs.springBootStarterValidation)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)

    testImplementation(project(":shared-testing"))
    testImplementation(libs.testcontainersJunit)
    testImplementation(kotlin("test"))
}
