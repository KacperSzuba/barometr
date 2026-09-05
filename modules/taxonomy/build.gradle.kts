plugins {
    id("barometr.jooq-codegen")
}

jooqCodegen {
    schema = "taxonomy"
}

// Which industries a law concerns.
//
// The knowledge asset rather than the algorithm: whatever decides the codes — a person
// today, a model later — writes its verdicts here, and everything that routes by
// industry reads them from one place. The alternative is each consumer inferring an
// industry from a title, which is four answers to the question the product is sold on.
dependencies {
    api(project(":shared"))
    // What can be classified: an act or a draft, in the vocabulary legislative defines.
    api(project(":legislative"))
    // A verdict may cite the document version and characters it was read from, which
    // is the same citation currency every other derived claim here uses.
    implementation(project(":corpus"))
    implementation(project(":platform"))

    implementation(libs.springBootStarter)
    implementation(libs.springBootStarterJooq)
    implementation(libs.jacksonModuleKotlin)
    implementation(libs.springModulithStarterCore)
    // Acts and drafts arrive as events from legislative; the register that persists and
    // redelivers them is wired in :app.
    implementation(libs.springModulithEventsApi)
    // Recording a verdict and reviewing the queue are operator endpoints. The chain
    // that authenticates them is the application's, so only the annotations are needed.
    implementation(libs.springBootStarterWeb)
    implementation(libs.springBootStarterValidation)
    implementation(libs.springSecurityCore)
    // How much of the archive carries an industry at all is the number that says
    // whether routing by PKD means anything yet.
    implementation(libs.springBootStarterActuator)
    // The walk over the archive is scheduled, and two replicas walking it at once would
    // spend the same budget twice. The lock provider is the application's; only the
    // annotation is needed here.
    implementation(libs.shedlockSpring)

    testImplementation(project(":shared-testing"))
    testImplementation(libs.testcontainersJunit)
    testImplementation(kotlin("test"))
}
