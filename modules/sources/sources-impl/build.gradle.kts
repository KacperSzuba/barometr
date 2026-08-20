plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "sources"
}

dependencies {
    api(project(":modules:sources:sources-api"))
    implementation(project(":shared:shared-kernel"))
    implementation(project(":platform:platform-persistence"))
    implementation(libs.springModulithStarterCore)
    implementation(libs.springBootStarterJooq)
    implementation(libs.jacksonModuleKotlin)

    testImplementation(project(":shared:shared-testing"))
    testImplementation(kotlin("test"))
}
