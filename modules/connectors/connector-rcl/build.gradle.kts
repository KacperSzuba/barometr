plugins {
    id("barometr.module")
}

dependencies {
    implementation(project(":modules:ingestion:ingestion-api"))
    implementation(project(":platform:platform-http"))
    implementation(libs.springBootStarter)
    // RCL publishes no API, so documents are read out of HTML. Selectors live in
    // configuration rather than in code — the same arrangement the BIP framework
    // will need for thousands of municipal sites.
    implementation(libs.jsoup)

    testImplementation(kotlin("test"))
}
