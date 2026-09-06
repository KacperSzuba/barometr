plugins {
    id("barometr.module")
}

// Search over what the system has derived: a derived index, never a source of truth.
//
// No jOOQ and no schema of its own — the index is rebuildable from Postgres at any
// time, which is the whole reason it is allowed to be a second datastore.
dependencies {
    api(project(":shared"))
    api(project(":legislative"))
    // What a document says, and where the archive keeps it: the text of a filed
    // document is announced by corpus and fetched from the blob store by the hash the
    // announcement carries, which is what that event exists to let a consumer do.
    implementation(project(":corpus"))
    implementation(project(":platform"))

    implementation(libs.springBootStarter)
    implementation(libs.springBootStarterElasticsearch)
    implementation(libs.springBootStarterWeb)
    // Rebuilding the index is an operator endpoint; the chain that authenticates it is
    // the application's, so only the annotations are needed here.
    implementation(libs.springSecurityCore)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)
    implementation(libs.springModulithEventsApi)
    // MeterRegistry: how much of the archive has reached the text index is a number to
    // watch rather than a query somebody remembers to run — an index quietly missing a
    // source's documents looks exactly like a source with nothing to say.
    implementation(libs.springBootStarterActuator)

    testImplementation(project(":shared-testing"))
    testImplementation(libs.testcontainersElasticsearch)
    testImplementation(libs.testcontainersJunit)
    testImplementation(kotlin("test"))
}
