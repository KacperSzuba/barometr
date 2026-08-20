plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "legislative"
}

// Acts, drafts, and the path a draft takes through the legislative process.
dependencies {
    api(project(":shared"))
    api(project(":corpus"))
    implementation(project(":platform"))

    implementation(libs.springModulithStarterCore)
}
