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

    implementation(libs.springBootStarter)
    implementation(libs.springBootStarterElasticsearch)
    implementation(libs.springBootStarterWeb)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)
    implementation(libs.springModulithEventsApi)

    testImplementation(project(":shared-testing"))
    testImplementation(libs.testcontainersElasticsearch)
    testImplementation(libs.testcontainersJunit)
    testImplementation(kotlin("test"))
}
