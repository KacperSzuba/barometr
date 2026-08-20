plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "corpus"
}

// Documents, their versions, and the text extracted from them. Schema first: the
// character offsets recorded here are what every downstream claim cites, so the
// shape had to be settled before anything was written against it.
dependencies {
    api(project(":shared"))
    // A document is the archived form of something a connector fetched.
    api(project(":ingestion"))
    implementation(project(":platform"))

    implementation(libs.springModulithStarterCore)
}
