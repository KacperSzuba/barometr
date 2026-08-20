plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "corpus"
}

dependencies {
    api(project(":modules:corpus:corpus-api"))
    implementation(project(":platform:platform-persistence"))
    implementation(libs.springModulithStarterCore)
}
