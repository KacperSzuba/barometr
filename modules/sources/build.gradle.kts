plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "sources"
}

// The registry of everything the system ingests from: what each source is, on what
// legal basis it may be read, how fast, and where each connector left off.
dependencies {
    api(project(":shared"))
    implementation(project(":platform"))

    implementation(libs.springModulithStarterCore)
    implementation(libs.springBootStarterJooq)
    implementation(libs.jacksonModuleKotlin)

    testImplementation(project(":shared-testing"))
    testImplementation(kotlin("test"))
}
