plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "legislative"
}

dependencies {
    api(project(":modules:legislative:legislative-api"))
    implementation(project(":platform:platform-persistence"))
    implementation(libs.springModulithStarterCore)
}
