plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "profiles"
}

// What a subscriber has told us they care about, and what that catches.
//
// Owning both is deliberate. The alert engine will route new documents to subscribers,
// and the profile screen has to show the same answer while somebody is still typing —
// two implementations of "does this profile care about that" would drift, and the one
// nobody watches is the one that sends the mail.
dependencies {
    api(project(":shared"))
    // A profile belongs to somebody, and that identifier is identity's to define.
    api(project(":identity"))
    implementation(project(":platform"))
    // Read through their published ports: what an address resolves to is legislative's
    // to say, and what a phrase finds is the index's.
    implementation(project(":legislative"))
    implementation(project(":search"))
    // Which industries a law concerns is taxonomy's answer, not a guess made from a
    // title here: the preview somebody watches while typing a code and the run that
    // sends the alert have to mean the same thing by it.
    implementation(project(":taxonomy"))

    implementation(libs.springBootStarter)
    implementation(libs.springBootStarterJooq)
    implementation(libs.springBootStarterWeb)
    implementation(libs.springBootStarterValidation)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)

    testImplementation(project(":shared-testing"))
    testImplementation(libs.testcontainersJunit)
    testImplementation(kotlin("test"))
}
