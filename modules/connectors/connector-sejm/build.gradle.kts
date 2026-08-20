plugins {
    id("barometr.module")
}

dependencies {
    // A connector sees the ingestion SPI and the HTTP platform. Nothing else —
    // no database, no object storage, no other module's internals.
    implementation(project(":modules:ingestion:ingestion-api"))
    implementation(project(":platform:platform-http"))
    implementation(libs.springBootStarter)
    implementation(libs.jacksonModuleKotlin)

    testImplementation(kotlin("test"))
}
