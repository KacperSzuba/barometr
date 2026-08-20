plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "ingestion"
}

dependencies {
    api(project(":modules:ingestion:ingestion-api"))
    implementation(project(":platform:platform-persistence"))
    implementation(project(":platform:platform-jobs"))
    implementation(project(":platform:platform-storage"))
    implementation(libs.springBootStarterWeb)
    // Operator endpoints are role-guarded; the chain that authenticates them is the
    // application's, so only the annotations are needed here.
    implementation(libs.springSecurityCore)
    implementation(libs.springBootStarterJooq)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)
    implementation(libs.shedlockSpring)
    implementation(libs.springBootStarterActuator)

    testImplementation(project(":shared:shared-testing"))
    testImplementation(libs.testcontainersJunit)
}
