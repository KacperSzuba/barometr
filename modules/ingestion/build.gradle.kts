plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "ingestion"
}

// The archive, and the connectors that fill it.
//
// The connectors live here rather than in projects of their own: a connector is
// only ever useful with the SPI it implements and the sink it writes to, and
// splitting them meant a context that could not be built, tested or extracted
// without three other projects coming along.
dependencies {
    api(project(":shared"))
    // Ingestion's contract names a source, so this is part of its surface.
    api(project(":sources"))
    implementation(project(":platform"))

    implementation(libs.springBootStarterWeb)
    // Operator endpoints are role-guarded; the chain that authenticates them is the
    // application's, so only the annotations are needed here.
    implementation(libs.springSecurityCore)
    implementation(libs.springBootStarterJooq)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)
    implementation(libs.shedlockSpring)
    implementation(libs.springBootStarterActuator)
    implementation(libs.springBootStarter)
    // HTML parsing for sources that publish no API. Selectors come from
    // configuration, so a layout change is an edit to YAML rather than to Kotlin.
    implementation(libs.jsoup)

    testImplementation(project(":shared-testing"))
    testImplementation(libs.testcontainersJunit)
    testImplementation(kotlin("test"))
}
